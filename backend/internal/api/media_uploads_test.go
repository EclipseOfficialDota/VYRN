package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/google/uuid"
)

func uploadRaw(f *fixture, method, path, token string, data []byte, want int) []byte {
	f.t.Helper()
	req, err := http.NewRequest(method, f.http.URL+path, bytes.NewReader(data))
	if err != nil {
		f.t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := f.http.Client().Do(req)
	if err != nil {
		f.t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != want {
		f.t.Fatalf("upload HTTP status got%d want%d", resp.StatusCode, want)
	}
	return body
}

func TestResumableMediaTransfers(t *testing.T) {
	f := setup(t)
	a, b, c := f.register("uploads-owner@test.invalid"), f.register("uploads-peer@test.invalid"), f.register("uploads-outsider@test.invalid")
	group := f.conversation(a, "group", b.User)
	id := uuid.NewString()
	path := "/v1/media-uploads/" + id
	first, last := []byte("first segment-"), []byte("final segment")
	full := append(append([]byte{}, first...), last...)
	checksum := md5.Sum(full)
	body := map[string]any{"conversation_id": group, "kind": "file", "filename": "resumed.bin", "part_count": 2, "md5": hex.EncodeToString(checksum[:])}
	uploadRaw(f, "PUT", path+"/parts/1", "", last, 401)
	uploadRaw(f, "PUT", path+"/parts/8192", a.Access, last, 400)
	uploadRaw(f, "PUT", path+"/parts/1", a.Access, last, 204)
	f.request("POST", path+"/complete", a.Access, body, 409)
	f.request("GET", path, b.Access, nil, 404)
	uploadRaw(f, "PUT", path+"/parts/0", b.Access, first, 404)
	f.request("DELETE", path, b.Access, nil, 404)
	var secondSession struct {
		Access string `json:"access_token"`
	}
	if json.Unmarshal(f.request("POST", "/v1/auth/login", "", map[string]any{"email": "uploads-owner@test.invalid", "password": "strong test password", "device_id": uuid.NewString(), "device_name": "Other upload session"}, 200), &secondSession) != nil {
		t.Fatal("second session")
	}
	f.request("GET", path, secondSession.Access, nil, 409)
	uploadRaw(f, "PUT", path+"/parts/0", secondSession.Access, first, 409)
	// Replace the HTTP server while retaining its database/files: no process-local upload state.
	server, err := New(f.db, f.s.cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	f.http.Close()
	f.s = server
	f.http = httptest.NewServer(server.Handler())
	t.Cleanup(f.http.Close)
	var status struct {
		State string       `json:"state"`
		Parts []uploadPart `json:"parts"`
	}
	if json.Unmarshal(f.request("GET", path, a.Access, nil, 200), &status) != nil || status.State != "open" || len(status.Parts) != 1 || status.Parts[0].Part != 1 {
		t.Fatal("upload status survives server replacement")
	}
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, first, 204)
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, []byte("changed"), 409)
	quota := f.s.cfg.QuotaBytes
	f.s.cfg.QuotaBytes = 1
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, first, 204)
	uploadRaw(f, "POST", "/v1/media?kind=file&conversation_id="+group, a.Access, []byte("new"), 507)
	f.s.cfg.QuotaBytes = quota
	var partKey string
	if f.db.QueryRow(context.Background(), `SELECT storage_key::text FROM media_upload_parts WHERE upload_id=$1 AND part=0`, id).Scan(&partKey) != nil {
		t.Fatal("part key")
	}
	if os.WriteFile(filepath.Join(f.s.cfg.MediaDir, partKey), []byte("damaged"), 0600) != nil {
		t.Fatal("damage test fixture")
	}
	f.request("POST", path+"/complete", a.Access, body, 409)
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, first, 204)
	private := f.conversation(a, "private", b.User)
	body["conversation_id"] = private
	f.request("POST", path+"/complete", a.Access, body, 422)
	body["conversation_id"] = group
	var completed map[string]any
	if json.Unmarshal(f.request("POST", path+"/complete", a.Access, body, 201), &completed) != nil {
		t.Fatal("complete response")
	}
	var repeat map[string]any
	if json.Unmarshal(f.request("POST", path+"/complete", a.Access, body, 200), &repeat) != nil || completed["id"] != repeat["id"] {
		t.Fatal("completion replay creates duplicate media")
	}
	sha := sha256.Sum256(full)
	if completed["sha256"] != hex.EncodeToString(sha[:]) || completed["size"] != float64(len(full)) {
		t.Fatal("assembled file hash or size")
	}
	media := completed["id"].(string)
	if !bytes.Equal(f.request("GET", "/v1/media/"+media, a.Access, nil, 200), full) {
		t.Fatal("assembled file bytes")
	}
	body["md5"] = strings.Repeat("0", 32)
	f.request("POST", path+"/complete", a.Access, body, 409)
	body["md5"] = hex.EncodeToString(checksum[:])
	uploadRaw(f, "PUT", path+"/parts/2", a.Access, []byte("late"), 409)
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, first, 204)
	f.request("GET", "/v1/media/"+media, b.Access, nil, 404)
	f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"resumable file"}`), MediaID: &media}, 201)
	if !bytes.Equal(f.request("GET", "/v1/media/"+media, b.Access, nil, 200), full) {
		t.Fatal("recipient file")
	}
	f.request("GET", "/v1/media/"+media, c.Access, nil, 404)
	otherGroup := f.conversation(a, "group", b.User)
	body["conversation_id"] = otherGroup
	var other map[string]any
	if json.Unmarshal(f.request("POST", path+"/complete", a.Access, body, 201), &other) != nil || other["id"] == media || other["sha256"] != completed["sha256"] {
		t.Fatal("shared uploader safely binds each destination")
	}
	f.request("DELETE", path, a.Access, nil, 204)
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, first, 409)
	f.request("POST", path+"/complete", a.Access, body, 409)
	if !bytes.Equal(f.request("GET", "/v1/media/"+media, b.Access, nil, 200), full) {
		t.Fatal("upload cancellation deletes published attachment")
	}
}

func TestUploadCancellationExpiryAndReserve(t *testing.T) {
	f := setup(t)
	a := f.register("upload-cancel@test.invalid")
	ctx := context.Background()
	path := "/v1/media-uploads/" + uuid.NewString()
	f.request("DELETE", path, a.Access, nil, 204)
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, []byte("late after cancellation"), 409)
	id := uuid.NewString()
	path = "/v1/media-uploads/" + id
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, []byte("retained until expiry"), 204)
	var key string
	if f.db.QueryRow(ctx, `SELECT storage_key::text FROM media_upload_parts WHERE upload_id=$1`, id).Scan(&key) != nil {
		t.Fatal("expiry fixture")
	}
	if _, err := f.db.Exec(ctx, `UPDATE media_uploads SET updated_at=now()-interval '25 hours' WHERE id=$1`, id); err != nil {
		t.Fatal(err)
	}
	if err := f.s.maintenance(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(f.s.cfg.MediaDir, key)); !os.IsNotExist(err) {
		t.Fatal("expired upload part retained")
	}
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, []byte("cannot resurrect expired upload"), 409)
	f.s.cfg.MinFreeBytes = ^uint64(0) - 1024
	uploadRaw(f, "PUT", "/v1/media-uploads/"+uuid.NewString()+"/parts/0", a.Access, []byte("reserve"), 507)
	f.s.cfg.MinFreeBytes = 1
	id = uuid.NewString()
	path = "/v1/media-uploads/" + id
	uploadRaw(f, "PUT", path+"/parts/0", a.Access, []byte("session revocation"), 204)
	if _, err := f.db.Exec(ctx, `UPDATE sessions SET revoked_at=now() WHERE id=(SELECT session_id FROM media_uploads WHERE id=$1)`, id); err != nil {
		t.Fatal(err)
	}
	if err := f.s.maintenance(ctx); err != nil {
		t.Fatal(err)
	}
	var count int
	if f.db.QueryRow(ctx, `SELECT count(*) FROM media_upload_parts WHERE upload_id=$1`, id).Scan(&count) != nil || count != 0 {
		t.Fatal("revoked session retains upload parts")
	}
	uploadRaw(f, "PUT", path+"/parts/1", a.Access, []byte("revoked"), 401)
}
