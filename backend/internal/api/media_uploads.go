package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"xyz.eclipseofficial/vyrn/internal/storage"
)

type uploadPart struct {
	Part   int    `json:"part"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
	key    string
}

// Use the same user/session lock order as key publication; revocation cannot race an ACK.
func (s *Server) lockUpload(w http.ResponseWriter, r *http.Request, tx pgx.Tx, create bool) (string, *int, bool) {
	p := current(r)
	id := r.PathValue("id")
	ctx := r.Context()
	if !validID(id) {
		fail(w, 400, "invalid_upload")
		return "", nil, false
	}
	var owner string
	if err := tx.QueryRow(ctx, `SELECT id::text FROM users WHERE id=$1 AND NOT disabled FOR UPDATE`, p.UserID).Scan(&owner); err != nil {
		fail(w, 401, "unauthorized")
		return "", nil, false
	}
	if err := lockKeySession(ctx, tx, p); err != nil {
		fail(w, 401, "unauthorized")
		return "", nil, false
	}
	if create {
		var count int
		if tx.QueryRow(ctx, `SELECT count(*) FROM media_uploads WHERE owner_id=$1 AND state='open'`, p.UserID).Scan(&count) != nil {
			s.internal(w)
			return "", nil, false
		}
		var existing bool
		if tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM media_uploads WHERE id=$1)`, id).Scan(&existing) != nil {
			s.internal(w)
			return "", nil, false
		}
		if !existing && count >= 32 {
			fail(w, 429, "upload_limit")
			return "", nil, false
		}
		if _, err := tx.Exec(ctx, `INSERT INTO media_uploads(id,owner_id,session_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`, id, p.UserID, p.SessionID); err != nil {
			s.internal(w)
			return "", nil, false
		}
	}
	var session, state string
	var final *int
	err := tx.QueryRow(ctx, `SELECT owner_id::text,session_id::text,state,final_parts FROM media_uploads WHERE id=$1 FOR UPDATE`, id).Scan(&owner, &session, &state, &final)
	if err == pgx.ErrNoRows || err == nil && owner != p.UserID {
		fail(w, 404, "not_found")
		return "", nil, false
	}
	if err != nil {
		s.internal(w)
		return "", nil, false
	}
	if session != p.SessionID {
		fail(w, 409, "upload_session_changed")
		return "", nil, false
	}
	if state == "cancelled" {
		fail(w, 409, "upload_cancelled")
		return "", nil, false
	}
	return state, final, true
}

func mediaUsage(ctx context.Context, tx pgx.Tx, user string) (int64, error) {
	var size int64
	err := tx.QueryRow(ctx, `SELECT COALESCE((SELECT sum(size) FROM media WHERE owner_id=$1),0)+COALESCE((SELECT sum(p.size) FROM media_upload_parts p JOIN media_uploads u ON u.id=p.upload_id WHERE u.owner_id=$1),0)`, user).Scan(&size)
	return size, err
}

func (s *Server) uploadPart(w http.ResponseWriter, r *http.Request) {
	part, err := strconv.Atoi(r.PathValue("part"))
	if err != nil || part < 0 || part >= 8192 || r.ContentLength < 1 || r.ContentLength > 1<<20 {
		fail(w, 400, "invalid_upload_part")
		return
	}
	select {
	case s.uploadSlots <- struct{}{}:
		defer func() { <-s.uploadSlots }()
	default:
		fail(w, 429, "busy")
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	state, final, ok := s.lockUpload(w, r, tx, true)
	if !ok {
		return
	}
	if final != nil && part >= *final {
		fail(w, 409, "upload_finalized")
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil || int64(len(data)) != r.ContentLength {
		fail(w, 400, "incomplete_upload")
		return
	}
	digest := sha256.Sum256(data)
	hash := hex.EncodeToString(digest[:])
	key := uuid.NewString()
	var oldSize int64
	var oldHash string
	err = tx.QueryRow(ctx, `SELECT size,sha256,storage_key::text FROM media_upload_parts WHERE upload_id=$1 AND part=$2`, r.PathValue("id"), part).Scan(&oldSize, &oldHash, &key)
	existing := err == nil
	if err != nil && err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	if existing {
		if oldSize != int64(len(data)) || oldHash != hash {
			fail(w, 409, "upload_part_changed")
			return
		}
		path, e := (storage.Local{Root: s.cfg.MediaDir}).Path(key)
		if e != nil {
			s.internal(w)
			return
		}
		input, e := os.Open(path)
		var stored []byte
		if e == nil {
			stored, e = io.ReadAll(io.LimitReader(input, oldSize+1))
			input.Close()
		}
		if e == nil && bytes.Equal(stored, data) {
			if _, e = tx.Exec(ctx, `UPDATE media_uploads SET updated_at=now() WHERE id=$1`, r.PathValue("id")); e != nil || tx.Commit(ctx) != nil {
				s.internal(w)
				return
			}
			w.WriteHeader(204)
			return
		}
	} else {
		if state != "open" {
			fail(w, 409, "upload_finalized")
			return
		}
		used, e := mediaUsage(ctx, tx, current(r).UserID)
		if e != nil {
			s.internal(w)
			return
		}
		if used+r.ContentLength > s.cfg.QuotaBytes {
			fail(w, 507, "quota_exceeded")
			return
		}
		var size int64
		if tx.QueryRow(ctx, `SELECT COALESCE(sum(size),0) FROM media_upload_parts WHERE upload_id=$1`, r.PathValue("id")).Scan(&size) != nil {
			s.internal(w)
			return
		}
		if size+r.ContentLength > s.cfg.UploadLimit || size+r.ContentLength > 250<<20 {
			fail(w, 413, "file_limit")
			return
		}
	}
	release, err := s.reserveMediaBytes(r.ContentLength)
	if errors.Is(err, errStorageReserve) {
		fail(w, 507, "storage_reserve")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	defer release()
	store := storage.Local{Root: s.cfg.MediaDir}
	writeKey := key
	if existing {
		writeKey = uuid.NewString()
	}
	path, err := store.Path(writeKey)
	if err != nil {
		s.internal(w)
		return
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		s.internal(w)
		return
	}
	keep := false
	defer func() {
		f.Close()
		if !keep {
			_ = os.Remove(path)
		}
	}()
	if _, err = f.Write(data); err != nil || f.Sync() != nil || f.Close() != nil {
		s.internal(w)
		return
	}
	if existing {
		target, e := store.Path(key)
		if e != nil || os.Rename(path, target) != nil {
			s.internal(w)
			return
		}
		keep = true
	}
	if !existing {
		if _, err = tx.Exec(ctx, `INSERT INTO media_upload_parts(upload_id,part,size,sha256,storage_key) VALUES($1,$2,$3,$4,$5)`, r.PathValue("id"), part, len(data), hash, key); err != nil {
			s.internal(w)
			return
		}
	}
	if _, err = tx.Exec(ctx, `UPDATE media_uploads SET updated_at=now() WHERE id=$1`, r.PathValue("id")); err != nil {
		s.internal(w)
		return
	}
	// A lost COMMIT response must not remove bytes that the database may reference.
	keep = true
	if tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) uploadStatus(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	p := current(r)
	var session, state string
	var final *int
	err := s.db.QueryRow(r.Context(), `SELECT session_id::text,state,final_parts FROM media_uploads WHERE id=$1 AND owner_id=$2`, id, p.UserID).Scan(&session, &state, &final)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	if session != p.SessionID {
		fail(w, 409, "upload_session_changed")
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT part,size,sha256 FROM media_upload_parts WHERE upload_id=$1 ORDER BY part`, id)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	parts := []uploadPart{}
	for rows.Next() {
		var item uploadPart
		if rows.Scan(&item.Part, &item.Size, &item.SHA256) != nil {
			s.internal(w)
			return
		}
		parts = append(parts, item)
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"id": id, "state": state, "final_parts": final, "parts": parts})
}

func (s *Server) completeUpload(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Conversation string `json:"conversation_id"`
		Kind         string `json:"kind"`
		Filename     string `json:"filename"`
		Parts        int    `json:"part_count"`
		MD5          string `json:"md5,omitempty"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validID(in.Conversation) || in.Parts < 1 || in.Parts > 8192 || utf8.RuneCountInString(in.Filename) < 1 || utf8.RuneCountInString(in.Filename) > 255 || strings.ContainsAny(in.Filename, "\r\n\x00/\\") || in.Kind != "file" && in.Kind != "image" && in.Kind != "voice" && in.Kind != "video" || in.MD5 != "" && len(in.MD5) != 32 {
		fail(w, 400, "invalid_upload")
		return
	}
	if in.MD5 != "" {
		if _, err := hex.DecodeString(in.MD5); err != nil {
			fail(w, 400, "invalid_upload")
			return
		}
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	_, final, ok := s.lockUpload(w, r, tx, false)
	if !ok {
		return
	}
	if final != nil && *final != in.Parts {
		fail(w, 409, "upload_finalized")
		return
	}
	kind, _, err := memberRole(ctx, tx, in.Conversation, current(r).UserID)
	if err != nil {
		fail(w, 404, "not_found")
		return
	}
	if kind != "group" {
		fail(w, 422, "e2ee_required")
		return
	}
	id := uuid.NewSHA1(uuid.NameSpaceURL, []byte("vyrn-media:"+current(r).UserID+":"+r.PathValue("id")+":"+in.Conversation)).String()
	var previousKind, name, hash string
	var previousSize int64
	err = tx.QueryRow(ctx, `SELECT kind,filename,size,sha256 FROM media WHERE id=$1 AND owner_id=$2`, id, current(r).UserID).Scan(&previousKind, &name, &previousSize, &hash)
	if err == nil {
		var expected string
		if tx.QueryRow(ctx, `SELECT whole_md5 FROM media_uploads WHERE id=$1`, r.PathValue("id")).Scan(&expected) != nil {
			s.internal(w)
			return
		}
		if previousKind != in.Kind || name != in.Filename || in.MD5 != "" && !strings.EqualFold(in.MD5, expected) {
			fail(w, 409, "upload_finalized")
			return
		}
		respond(w, 200, map[string]any{"id": id, "size": previousSize, "sha256": hash})
		return
	}
	if err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	rows, err := tx.Query(ctx, `SELECT part,size,sha256,storage_key::text FROM media_upload_parts WHERE upload_id=$1 ORDER BY part`, r.PathValue("id"))
	if err != nil {
		s.internal(w)
		return
	}
	parts := []uploadPart{}
	var size int64
	for rows.Next() {
		var item uploadPart
		if rows.Scan(&item.Part, &item.Size, &item.SHA256, &item.key) != nil {
			rows.Close()
			s.internal(w)
			return
		}
		size += item.Size
		parts = append(parts, item)
	}
	rows.Close()
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	if len(parts) != in.Parts {
		fail(w, 409, "upload_incomplete")
		return
	}
	for index, item := range parts {
		if item.Part != index {
			fail(w, 409, "upload_incomplete")
			return
		}
	}
	if size > s.cfg.UploadLimit || size > 250<<20 || in.Kind == "voice" && size > 100<<20 {
		fail(w, 413, "file_limit")
		return
	}
	used, err := mediaUsage(ctx, tx, current(r).UserID)
	if err != nil {
		s.internal(w)
		return
	}
	if used+size > s.cfg.QuotaBytes {
		fail(w, 507, "quota_exceeded")
		return
	}
	release, err := s.reserveMediaBytes(size)
	if errors.Is(err, errStorageReserve) {
		fail(w, 507, "storage_reserve")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	defer release()
	store := storage.Local{Root: s.cfg.MediaDir}
	path, err := store.Path(uuid.NewString())
	if err != nil {
		s.internal(w)
		return
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		s.internal(w)
		return
	}
	defer func() { f.Close(); _ = os.Remove(path) }()
	sha, checksum := sha256.New(), md5.New()
	for _, item := range parts {
		partPath, e := store.Path(item.key)
		if e != nil {
			s.internal(w)
			return
		}
		input, e := os.Open(partPath)
		if e != nil {
			fail(w, 409, "upload_part_unavailable")
			return
		}
		partHash := sha256.New()
		copied, e := io.Copy(io.MultiWriter(f, sha, checksum, partHash), io.LimitReader(input, item.Size+1))
		input.Close()
		if e != nil || copied != item.Size || hex.EncodeToString(partHash.Sum(nil)) != item.SHA256 {
			fail(w, 409, "upload_part_unavailable")
			return
		}
	}
	if in.MD5 != "" && !strings.EqualFold(in.MD5, hex.EncodeToString(checksum.Sum(nil))) {
		fail(w, 409, "upload_checksum_changed")
		return
	}
	if f.Sync() != nil || f.Close() != nil {
		s.internal(w)
		return
	}
	target, err := store.Path(id)
	if err != nil {
		s.internal(w)
		return
	}
	contentType := "application/octet-stream"
	if in.Kind == "image" {
		config, format, e := avatarImage(path, false)
		if e != nil || config.Width < 1 {
			fail(w, 400, "invalid_image")
			return
		}
		contentType = "image/" + format
	}
	if os.Rename(path, target) != nil {
		s.internal(w)
		return
	}
	committed := false
	defer func() {
		if !committed {
			_ = os.Remove(target)
		}
	}()
	hash = hex.EncodeToString(sha.Sum(nil))
	if _, err = tx.Exec(ctx, `INSERT INTO media(id,owner_id,conversation_id,kind,filename,content_type,size,sha256,storage_key,encrypted) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,false)`, id, current(r).UserID, in.Conversation, in.Kind, in.Filename, contentType, size, hash, id); err != nil {
		s.internal(w)
		return
	}
	if _, err = tx.Exec(ctx, `UPDATE media_uploads SET state='complete',final_parts=$2,whole_md5=$3,updated_at=now() WHERE id=$1`, r.PathValue("id"), in.Parts, hex.EncodeToString(checksum.Sum(nil))); err != nil {
		s.internal(w)
		return
	}
	committed = true
	if tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 201, map[string]any{"id": id, "size": size, "sha256": hash})
}

func (s *Server) cancelUpload(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 400, "invalid_upload")
		return
	}
	ctx := r.Context()
	p := current(r)
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `INSERT INTO media_uploads(id,owner_id,session_id,state) VALUES($1,$2,$3,'cancelled') ON CONFLICT DO NOTHING`, id, p.UserID, p.SessionID); err != nil {
		s.internal(w)
		return
	}
	var owner, session string
	if err = tx.QueryRow(ctx, `SELECT owner_id::text,session_id::text FROM media_uploads WHERE id=$1 FOR UPDATE`, id).Scan(&owner, &session); err != nil {
		s.internal(w)
		return
	}
	if owner != p.UserID {
		fail(w, 404, "not_found")
		return
	}
	if session != p.SessionID {
		fail(w, 409, "upload_session_changed")
		return
	}
	if lockKeySession(ctx, tx, p) != nil {
		fail(w, 401, "unauthorized")
		return
	}
	if _, err = tx.Exec(ctx, `UPDATE media_uploads SET state='cancelled',updated_at=now() WHERE id=$1`, id); err != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	if err = s.cleanUploadParts(ctx, id); err != nil {
		s.log.Error("upload_cleanup_failed", "category", "database_or_io")
	}
	w.WriteHeader(204)
}

func (s *Server) cleanUploadParts(ctx context.Context, id string) error {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var state string
	if err = tx.QueryRow(ctx, `SELECT state FROM media_uploads WHERE id=$1 FOR UPDATE`, id).Scan(&state); err != nil {
		return err
	}
	if state != "cancelled" {
		return nil
	}
	rows, err := tx.Query(ctx, `SELECT storage_key::text FROM media_upload_parts WHERE upload_id=$1`, id)
	if err != nil {
		return err
	}
	keys := []string{}
	for rows.Next() {
		var key string
		if err = rows.Scan(&key); err != nil {
			rows.Close()
			return err
		}
		keys = append(keys, key)
	}
	rows.Close()
	if rows.Err() != nil {
		return rows.Err()
	}
	store := storage.Local{Root: s.cfg.MediaDir}
	for _, key := range keys {
		if err = store.Delete(ctx, key); err != nil {
			return err
		}
	}
	if _, err = tx.Exec(ctx, `DELETE FROM media_upload_parts WHERE upload_id=$1`, id); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Server) expireUploads(ctx context.Context) error {
	rows, err := s.db.Query(ctx, `SELECT u.id::text FROM media_uploads u WHERE (u.state='cancelled' AND EXISTS(SELECT 1 FROM media_upload_parts p WHERE p.upload_id=u.id)) OR (u.state<>'cancelled' AND (u.updated_at<now()-interval '24 hours' OR NOT EXISTS(SELECT 1 FROM sessions s WHERE s.id=u.session_id AND s.user_id=u.owner_id AND s.revoked_at IS NULL AND s.refresh_expires>now()))) LIMIT 100`)
	if err != nil {
		return err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if rows.Err() != nil {
		return rows.Err()
	}
	for _, id := range ids {
		if _, err = s.db.Exec(ctx, `UPDATE media_uploads u SET state='cancelled' WHERE id=$1 AND ((state='cancelled') OR updated_at<now()-interval '24 hours' OR NOT EXISTS(SELECT 1 FROM sessions s WHERE s.id=u.session_id AND s.user_id=u.owner_id AND s.revoked_at IS NULL AND s.refresh_expires>now()))`, id); err != nil {
			return err
		}
		if err = s.cleanUploadParts(ctx, id); err != nil {
			return err
		}
	}
	return nil
}
