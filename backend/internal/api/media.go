package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
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
	if used, err = mediaUsage(ctx, tx, p.UserID); err != nil {
		s.internal(w)
		return
	}
	if used+r.ContentLength > s.cfg.QuotaBytes {
		fail(w, 507, "quota_exceeded")
		return
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
	if kind == "avatar" {
		config, format, imageErr := avatarImage(path, true)
		if imageErr != nil || config.Width < 1 {
			fail(w, 400, "invalid_avatar_image")
			return
		}
		contentType = "image/" + format
	}
	_, err = tx.Exec(ctx, `INSERT INTO media(id,owner_id,conversation_id,kind,filename,content_type,size,sha256,storage_key,encrypted) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$10,$9)`, id, p.UserID, conv, kind, name, contentType, size, hex.EncodeToString(hash.Sum(nil)), encrypted, id)
	if err != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	keep = true
	respond(w, 201, map[string]any{"id": id, "size": size, "sha256": hex.EncodeToString(hash.Sum(nil))})
}

// Bound decoded memory before fully validating a static profile image.
func avatarImage(path string, validate bool) (image.Config, string, error) {
	f, err := os.Open(path)
	if err != nil {
		return image.Config{}, "", err
	}
	defer f.Close()
	config, format, err := image.DecodeConfig(f)
	if err != nil {
		return config, format, err
	}
	if (format != "jpeg" && format != "png") || config.Width < 1 || config.Height < 1 || config.Width > 4096 || config.Height > 4096 {
		return image.Config{}, "", errors.New("invalid_avatar_dimensions")
	}
	if validate {
		if _, err = f.Seek(0, 0); err != nil {
			return config, format, err
		}
		_, _, err = image.Decode(f)
	}
	return config, format, err
}

func (s *Server) avatar(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var mediaID, key, contentType string
	var size int64
	var created time.Time
	err := s.db.QueryRow(r.Context(), `SELECT f.id::text,f.storage_key,f.content_type,f.size,f.created_at FROM users u JOIN media f ON f.id=u.avatar_id WHERE u.id=$1 AND NOT u.disabled AND f.kind='avatar' AND NOT f.encrypted`, id).Scan(&mediaID, &key, &contentType, &size, &created)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	path, err := (storage.Local{Root: s.cfg.MediaDir}).Path(key)
	if err != nil {
		s.internal(w)
		return
	}
	config, format, err := avatarImage(path, false)
	if err != nil {
		fail(w, 404, "not_found")
		return
	}
	respond(w, 200, map[string]any{"id": mediaID, "size": size, "width": config.Width, "height": config.Height, "date": created, "content_type": "image/" + format})
}

func (s *Server) deleteAvatar(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 400, "invalid_request")
		return
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(r.Context())
	var avatar *string
	if err = tx.QueryRow(r.Context(), `SELECT avatar_id::text FROM users WHERE id=$1 FOR UPDATE`, current(r).UserID).Scan(&avatar); err != nil {
		s.internal(w)
		return
	}
	if avatar == nil {
		w.WriteHeader(204)
		return
	}
	if *avatar != id {
		fail(w, 409, "profile_changed")
		return
	}
	if _, err = tx.Exec(r.Context(), `UPDATE users SET avatar_id=NULL WHERE id=$1`, current(r).UserID); err != nil {
		s.internal(w)
		return
	}
	if err = emitProfileUpdate(r.Context(), tx, current(r).UserID); err != nil {
		s.internal(w)
		return
	}
	if err = tx.Commit(r.Context()); err != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}

var errStorageReserve = errors.New("storage_reserve")

func (s *Server) reserveMediaBytes(size int64) (func(), error) {
	s.storageMu.Lock()
	defer s.storageMu.Unlock()
	free, err := storage.FreeBytes(s.cfg.MediaDir)
	if err != nil {
		return nil, err
	}
	if size < 1 || free < s.cfg.MinFreeBytes || free-s.cfg.MinFreeBytes < s.reservedBytes || free-s.cfg.MinFreeBytes-s.reservedBytes < uint64(size) {
		return nil, errStorageReserve
	}
	s.reservedBytes += uint64(size)
	return func() { s.storageMu.Lock(); s.reservedBytes -= uint64(size); s.storageMu.Unlock() }, nil
}

type mediaDetails struct {
	ID           string    `json:"id"`
	Kind         string    `json:"kind"`
	Filename     string    `json:"filename"`
	ContentType  string    `json:"content_type"`
	Size         int64     `json:"size"`
	SHA256       string    `json:"sha256"`
	Encrypted    bool      `json:"encrypted"`
	CreatedAt    time.Time `json:"created_at"`
	Conversation *string   `json:"conversation_id"`
	storageKey   string
}

// Metadata and bytes must use the same live-message membership boundary.
func (s *Server) accessibleMedia(ctx context.Context, id, user string) (mediaDetails, error) {
	var value mediaDetails
	err := s.db.QueryRow(ctx, `SELECT f.id::text,f.kind,f.filename,f.content_type,f.size,f.sha256,f.encrypted,f.created_at,f.conversation_id::text,f.storage_key FROM media f WHERE f.id=$1 AND (f.owner_id=$2 OR EXISTS(SELECT 1 FROM users u WHERE u.avatar_id=f.id AND NOT u.disabled) OR EXISTS(SELECT 1 FROM messages msg JOIN members m ON m.conversation_id=msg.conversation_id WHERE msg.media_id=f.id AND m.user_id=$2 AND msg.deleted_at IS NULL AND (msg.expires_at IS NULL OR msg.expires_at>now())))`, id, user).Scan(&value.ID, &value.Kind, &value.Filename, &value.ContentType, &value.Size, &value.SHA256, &value.Encrypted, &value.CreatedAt, &value.Conversation, &value.storageKey)
	return value, err
}

func (s *Server) mediaMetadata(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	value, err := s.accessibleMedia(r.Context(), id, current(r).UserID)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	path, err := (storage.Local{Root: s.cfg.MediaDir}).Path(value.storageKey)
	if err != nil {
		s.internal(w)
		return
	}
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != value.Size {
		fail(w, 404, "not_found")
		return
	}
	respond(w, 200, value)
}

func (s *Server) download(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	value, err := s.accessibleMedia(r.Context(), id, current(r).UserID)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	store := storage.Local{Root: s.cfg.MediaDir}
	path, err := store.Path(value.storageKey)
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
	if err != nil || !info.Mode().IsRegular() || info.Size() != value.Size {
		fail(w, 404, "not_found")
		return
	}
	w.Header().Set("Content-Type", value.ContentType)
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": value.Filename}))
	w.Header().Set("Content-Length", strconv.FormatInt(value.Size, 10))
	w.Header().Set("ETag", `"`+value.SHA256+`"`)
	http.ServeContent(w, r, value.Filename, info.ModTime(), f)
}
