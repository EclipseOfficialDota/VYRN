package api

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"xyz.eclipseofficial/vyrn/internal/storage"
)

func (s *Server) upload(w http.ResponseWriter, r *http.Request) {
	p := current(r)
	kind := r.URL.Query().Get("kind")
	conversation := r.URL.Query().Get("conversation_id")
	encrypted := r.URL.Query().Get("encrypted") == "true"
	limit := s.cfg.UploadLimit
	switch kind {
	case "avatar":
		if limit > 10<<20 {
			limit = 10 << 20
		}
	case "voice":
		if limit > 100<<20 {
			limit = 100 << 20
		}
	case "file", "image", "video":
	default:
		fail(w, 400, "invalid_kind")
		return
	}
	if kind != "avatar" && !validID(conversation) || kind == "avatar" && (conversation != "" || encrypted) {
		fail(w, 400, "invalid_request")
		return
	}
	if r.ContentLength <= 0 || r.ContentLength > limit {
		fail(w, 413, "file_limit")
		return
	}
	name := strings.TrimSpace(r.Header.Get("X-Filename"))
	if name == "" {
		name = "attachment"
	}
	if utf8.RuneCountInString(name) > 255 || strings.ContainsAny(name, "\r\n\x00/\\") {
		fail(w, 400, "invalid_filename")
		return
	}
	if !s.limiter.Allow("upload:"+p.UserID, 10, minute) {
		fail(w, 429, "rate_limited")
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
	var user string
	if tx.QueryRow(ctx, `SELECT id::text FROM users WHERE id=$1 FOR UPDATE`, p.UserID).Scan(&user) != nil {
		s.internal(w)
		return
	}
	if conversation != "" {
		k, _, e := memberRole(ctx, tx, conversation, p.UserID)
		if e != nil {
			fail(w, 404, "not_found")
			return
		}
		if (k == "private") != encrypted {
			fail(w, 422, "encryption_required")
			return
		}
	}
	var used int64
	if tx.QueryRow(ctx, `SELECT COALESCE(sum(size),0) FROM media WHERE owner_id=$1`, p.UserID).Scan(&used) != nil {
		s.internal(w)
		return
	}
	if used+r.ContentLength > s.cfg.QuotaBytes {
		fail(w, 507, "quota_exceeded")
		return
	}
	s.storageMu.Lock()
	free, err := storage.FreeBytes(s.cfg.MediaDir)
	if err != nil {
		s.storageMu.Unlock()
		s.internal(w)
		return
	}
	// Reserve the full upload before writing; simultaneous requests must not each
	// spend the same free disk space. Subtraction avoids uint64 overflow.
	if free < s.cfg.MinFreeBytes || free-s.cfg.MinFreeBytes < s.reservedBytes || free-s.cfg.MinFreeBytes-s.reservedBytes < uint64(r.ContentLength) {
		s.storageMu.Unlock()
		fail(w, 507, "storage_reserve")
		return
	}
	s.reservedBytes += uint64(r.ContentLength)
	s.storageMu.Unlock()
	defer func() {
		s.storageMu.Lock()
		s.reservedBytes -= uint64(r.ContentLength)
		s.storageMu.Unlock()
	}()
	id := uuid.NewString()
	path := filepath.Join(s.cfg.MediaDir, id)
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
	hash := sha256.New()
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	size, err := io.Copy(io.MultiWriter(f, hash), r.Body)
	if err != nil || size != r.ContentLength {
		fail(w, 400, "incomplete_upload")
		return
	}
	if f.Sync() != nil || f.Close() != nil {
		s.internal(w)
		return
	}
	var conv any
	if conversation != "" {
		conv = conversation
	}
	contentType := "application/octet-stream"
	_, err = tx.Exec(ctx, `INSERT INTO media(id,owner_id,conversation_id,kind,filename,content_type,size,sha256,storage_key,encrypted) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$10,$9)`, id, p.UserID, conv, kind, name, contentType, size, hex.EncodeToString(hash.Sum(nil)), encrypted, id)
	if err != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	keep = true
	respond(w, 201, map[string]any{"id": id, "size": size, "sha256": hex.EncodeToString(hash.Sum(nil))})
}
func (s *Server) download(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var key, name, contentType string
	var size int64
	err := s.db.QueryRow(r.Context(), `SELECT storage_key,filename,content_type,size FROM media f WHERE f.id=$1 AND (f.owner_id=$2 OR EXISTS(SELECT 1 FROM users u WHERE u.avatar_id=f.id AND NOT u.disabled) OR EXISTS(SELECT 1 FROM messages msg JOIN members m ON m.conversation_id=msg.conversation_id WHERE msg.media_id=f.id AND m.user_id=$2 AND msg.deleted_at IS NULL AND (msg.expires_at IS NULL OR msg.expires_at>now())))`, id, current(r).UserID).Scan(&key, &name, &contentType, &size)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	store := storage.Local{Root: s.cfg.MediaDir}
	path, err := store.Path(key)
	if err != nil {
		s.internal(w)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		fail(w, 404, "not_found")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		s.internal(w)
		return
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	http.ServeContent(w, r, name, info.ModTime(), f)
}
