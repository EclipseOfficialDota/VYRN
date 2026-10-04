package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/pquerna/otp/totp"
	"xyz.eclipseofficial/vyrn/internal/database"
	"xyz.eclipseofficial/vyrn/internal/security"
)

type fixture struct {
	t            *testing.T
	s            *Server
	http         *httptest.Server
	db           *pgxpool.Pool
	invite, totp string
}
type account struct{ User, Access, Refresh, Device, Recovery string }

func setup(t *testing.T) *fixture {
	t.Helper()
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		t.Skip("DATABASE_URL required for PostgreSQL integration tests")
	}
	ctx := context.Background()
	db, err := database.Open(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	schema := "test_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err = db.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		t.Fatal(err)
	}
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	cfg.ConnConfig.RuntimeParams["application_name"] = schema
	testdb, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { testdb.Close(); _, _ = db.Exec(ctx, `DROP SCHEMA `+schema+` CASCADE`); db.Close() })
	if err = database.Migrate(ctx, testdb); err != nil {
		t.Fatal(err)
	}
	if err = database.Migrate(ctx, testdb); err != nil {
		t.Fatal("repeat migration", err)
	}
	key, err := totp.Generate(totp.GenerateOpts{Issuer: "VYRN test", AccountName: "admin@test.invalid"})
	if err != nil {
		t.Fatal(err)
	}
	server, err := New(testdb, Config{MediaDir: t.TempDir(), MinFreeBytes: 1, AdminTOTPSecret: key.Secret()}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	httpServer := httptest.NewServer(server.Handler())
	t.Cleanup(httpServer.Close)
	invite, err := security.Token()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = testdb.Exec(ctx, `INSERT INTO invites(id,code_hash,remaining,expires_at) VALUES($1,$2,1000,now()+interval '1 day')`, uuid.NewString(), security.Digest(invite)); err != nil {
		t.Fatal(err)
	}
	return &fixture{t, server, httpServer, testdb, invite, key.Secret()}
}
func (f *fixture) request(method, path, token string, input any, want int) []byte {
	f.t.Helper()
	var body io.Reader
	if input != nil {
		data, err := json.Marshal(input)
		if err != nil {
			f.t.Fatal(err)
		}
		body = bytes.NewReader(data)
	}
	req, _ := http.NewRequest(method, f.http.URL+path, body)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := f.http.Client().Do(req)
	if err != nil {
		f.t.Fatal(err)
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	if res.StatusCode != want {
		f.t.Fatalf("%s %s: status %d, want %d", method, path, res.StatusCode, want)
	}
	return data
}
func (f *fixture) register(email string) account {
	f.t.Helper()
	device := uuid.NewString()
	data := f.request("POST", "/v1/auth/register", "", map[string]any{"email": email, "password": "strong test password", "invite_code": f.invite, "display_name": "Test user", "device_id": device, "device_name": "Test device"}, 201)
	var pair tokenPair
	if json.Unmarshal(data, &pair) != nil {
		f.t.Fatal("decode registration")
	}
	return account{pair.UserID, pair.Access, pair.Refresh, device, pair.RecoveryCode}
}
func (f *fixture) conversation(a account, kind string, users ...string) string {
	f.t.Helper()
	data := f.request("POST", "/v1/conversations", a.Access, map[string]any{"kind": kind, "title": "Test group", "members": users}, 201)
	var out map[string]any
	_ = json.Unmarshal(data, &out)
	return out["id"].(string)
}
func TestIntegrationAuth(t *testing.T) {
	f := setup(t)
	a := f.register("USER@Example.com")
	f.request("POST", "/v1/auth/register", "", map[string]any{"email": "user@example.com", "password": "strong test password", "invite_code": f.invite, "display_name": "Other", "device_id": uuid.NewString(), "device_name": "Duplicate"}, 400)
	f.request("GET", "/v1/me", a.Access, nil, 200)
	for i := 0; i < 2; i++ {
		f.request("POST", "/v1/auth/login", "", map[string]any{"email": "user@example.com", "password": "strong test password", "device_id": uuid.NewString(), "device_name": "Another device"}, 200)
	}
	f.request("POST", "/v1/auth/login", "", map[string]any{"email": "user@example.com", "password": "strong test password", "device_id": uuid.NewString(), "device_name": "Fourth device"}, 409)
	data := f.request("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": a.Refresh}, 200)
	var pair tokenPair
	_ = json.Unmarshal(data, &pair)
	f.request("GET", "/v1/me", a.Access, nil, 401)
	f.request("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": a.Refresh}, 401)
	f.request("GET", "/v1/me", pair.Access, nil, 401)
	f.request("POST", "/v1/auth/recover", "", map[string]string{"email": "user@example.com", "recovery_code": a.Recovery, "new_password": "replacement password"}, 200)
	f.request("POST", "/v1/auth/recover", "", map[string]string{"email": "user@example.com", "recovery_code": a.Recovery, "new_password": "another password"}, 400)
}
func TestIntegrationMessagesAndPermissions(t *testing.T) {
	f := setup(t)
	a := f.register("a@example.com")
	b := f.register("b@example.com")
	outsider := f.register("c@example.com")
	group := f.conversation(a, "group", b.User)
	path := "/v1/conversations/" + group + "/messages"
	client := uuid.NewString()
	input := messageInput{ClientID: client, Payload: json.RawMessage(`{"text":"سلام VYRN"}`)}
	data := f.request("POST", path, a.Access, input, 201)
	var message map[string]string
	_ = json.Unmarshal(data, &message)
	id := message["id"]
	f.request("POST", path, a.Access, input, 200)
	input.Payload = json.RawMessage(`{"text":"changed"}`)
	f.request("POST", path, a.Access, input, 409)
	f.request("GET", path, b.Access, nil, 200)
	f.request("GET", path, outsider.Access, nil, 404)
	f.request("PATCH", "/v1/messages/"+id, b.Access, map[string]any{"payload": map[string]string{"text": "hijack"}, "revision": 1}, 403)
	f.request("PATCH", "/v1/messages/"+id, a.Access, map[string]any{"payload": map[string]string{"text": "edited"}, "revision": 1}, 204)
	f.request("PATCH", "/v1/messages/"+id, a.Access, map[string]any{"payload": map[string]string{"text": "stale"}, "revision": 1}, 409)
	f.request("PUT", "/v1/messages/"+id+"/reactions", b.Access, map[string]string{"emoji": "👍"}, 204)
	f.request("PUT", "/v1/messages/"+id+"/receipt", b.Access, map[string]bool{"read": true}, 204)
	f.request("PUT", "/v1/messages/"+id+"/pin", b.Access, map[string]bool{"pinned": true}, 403)
	f.request("PUT", "/v1/messages/"+id+"/pin", a.Access, map[string]bool{"pinned": true}, 204)
	var visible []struct {
		Reactions []struct {
			Emoji string `json:"emoji"`
			Count int    `json:"count"`
			Mine  bool   `json:"mine"`
		} `json:"reactions"`
		Receipts struct {
			Delivered int  `json:"delivered"`
			Read      int  `json:"read"`
			ReadByMe  bool `json:"read_by_me"`
		} `json:"receipts"`
	}
	if json.Unmarshal(f.request("GET", path, b.Access, nil, 200), &visible) != nil || len(visible) != 1 || len(visible[0].Reactions) != 1 || visible[0].Reactions[0].Emoji != "👍" || visible[0].Reactions[0].Count != 1 || !visible[0].Reactions[0].Mine || visible[0].Receipts.Delivered != 1 || visible[0].Receipts.Read != 1 || !visible[0].Receipts.ReadByMe {
		t.Fatal("reaction and receipt read model did not match mutations")
	}
	f.request("PUT", "/v1/messages/"+id+"/receipt", b.Access, map[string]bool{"read": false}, 204)
	if json.Unmarshal(f.request("GET", path, b.Access, nil, 200), &visible) != nil || visible[0].Receipts.Read != 1 {
		t.Fatal("late delivery receipt cleared read state")
	}
	private := f.conversation(a, "private", b.User)
	f.request("POST", "/v1/conversations/"+private+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"must not leak"}`)}, 422)
	f.request("DELETE", "/v1/messages/"+id, a.Access, nil, 204)
	data = f.request("GET", path, b.Access, nil, 200)
	if bytes.Contains(data, []byte("edited"+`"`)) {
		t.Fatal("deleted plaintext retained")
	}
	f.request("DELETE", "/v1/conversations/"+group+"/members/"+b.User, a.Access, nil, 204)
	f.request("GET", path, b.Access, nil, 404)
}

func TestIntegrationExpiryAndMediaCollection(t *testing.T) {
	f := setup(t)
	a := f.register("expiry@example.com")
	group := f.conversation(a, "group")
	ctx := context.Background()
	insertFile := func() string {
		t.Helper()
		id := uuid.NewString()
		if err := os.WriteFile(filepath.Join(f.s.cfg.MediaDir, id), []byte("fixture"), 0600); err != nil {
			t.Fatal(err)
		}
		_, err := f.db.Exec(ctx, `INSERT INTO media(id,owner_id,conversation_id,kind,filename,content_type,size,sha256,storage_key,encrypted,orphaned_at) VALUES($1,$2,$3,'file','fixture','application/octet-stream',7,'fixture',$4,false,now()-interval '8 days')`, id, a.User, group, id)
		if err != nil {
			t.Fatal(err)
		}
		return id
	}
	orphan, attached := insertFile(), insertFile()
	data := f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"expiring fixture"}`), MediaID: &attached}, 201)
	var created map[string]string
	if json.Unmarshal(data, &created) != nil {
		t.Fatal("message response")
	}
	if err := f.s.maintenance(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(f.s.cfg.MediaDir, orphan)); !os.IsNotExist(err) {
		t.Fatal("old orphan not collected")
	}
	if _, err := os.Stat(filepath.Join(f.s.cfg.MediaDir, attached)); err != nil {
		t.Fatal("referenced attachment collected")
	}
	var count int
	if err := f.db.QueryRow(ctx, `SELECT count(*) FROM media WHERE id=$1 AND orphaned_at IS NULL`, attached).Scan(&count); err != nil || count != 1 {
		t.Fatal("reattached media orphan clock not reset")
	}
	if _, err := f.db.Exec(ctx, `UPDATE messages SET expires_at=now()-interval '1 second' WHERE id=$1`, created["id"]); err != nil {
		t.Fatal(err)
	}
	f.request("GET", "/v1/media/"+attached, a.Access, nil, 200)
	if err := f.s.maintenance(ctx); err != nil {
		t.Fatal(err)
	}
	var tombstone bool
	if err := f.db.QueryRow(ctx, `SELECT deleted_at IS NOT NULL AND payload='{}'::jsonb AND media_id IS NULL AND revision=2 FROM messages WHERE id=$1`, created["id"]).Scan(&tombstone); err != nil || !tombstone {
		t.Fatal("expired message content not removed")
	}
	if err := f.db.QueryRow(ctx, `SELECT count(*) FROM events WHERE kind='message.expired' AND body->>'message_id'=$1`, created["id"]).Scan(&count); err != nil || count != 1 {
		t.Fatal("expiry event missing")
	}
	if _, err := os.Stat(filepath.Join(f.s.cfg.MediaDir, attached)); err != nil {
		t.Fatal("newly orphaned file did not retain grace period")
	}
	if err := f.s.maintenance(ctx); err != nil {
		t.Fatal(err)
	}
	if err := f.db.QueryRow(ctx, `SELECT count(*) FROM events WHERE kind='message.expired' AND body->>'message_id'=$1`, created["id"]).Scan(&count); err != nil || count != 1 {
		t.Fatal("expiry worker emitted duplicate event")
	}
	if _, err := f.db.Exec(ctx, `UPDATE media SET orphaned_at=now()-interval '8 days' WHERE id=$1`, attached); err != nil {
		t.Fatal(err)
	}
	if err := f.s.maintenance(ctx); err != nil {
		t.Fatal(err)
	}
	f.request("GET", "/v1/media/"+attached, a.Access, nil, 404)
}
func TestIntegrationMedia(t *testing.T) {
	f := setup(t)
	a := f.register("a@example.com")
	b := f.register("b@example.com")
	c := f.register("c@example.com")
	group := f.conversation(a, "group", b.User)
	req, _ := http.NewRequest("POST", f.http.URL+"/v1/media?kind=file&conversation_id="+group, strings.NewReader("test file content"))
	req.Header.Set("Authorization", "Bearer "+a.Access)
	req.Header.Set("X-Filename", "sample.txt")
	res, err := f.http.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 201 {
		t.Fatalf("upload status: %d", res.StatusCode)
	}
	var media map[string]any
	if json.NewDecoder(res.Body).Decode(&media) != nil {
		t.Fatal("decode media")
	}
	id := media["id"].(string)
	f.request("GET", "/v1/media/"+id, b.Access, nil, 404)
	f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"file"}`), MediaID: &id}, 201)
	if string(f.request("GET", "/v1/media/"+id, b.Access, nil, 200)) != "test file content" {
		t.Fatal("media corrupted")
	}
	f.request("GET", "/v1/media/"+id, c.Access, nil, 404)
	f.s.cfg.MinFreeBytes = ^uint64(0) - uint64(1024)
	req, _ = http.NewRequest("POST", f.http.URL+"/v1/media?kind=file&conversation_id="+group, strings.NewReader("blocked"))
	req.Header.Set("Authorization", "Bearer "+a.Access)
	res, err = f.http.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 507 {
		t.Fatalf("disk reserve status %d", res.StatusCode)
	}
	f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"text survives disk reserve"}`)}, 201)
}
func TestIntegrationWebSocketResume(t *testing.T) {
	f := setup(t)
	a := f.register("a@example.com")
	b := f.register("b@example.com")
	group := f.conversation(a, "group", b.User)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	dial := func(cursor int64) *websocket.Conn {
		t.Helper()
		header := http.Header{}
		header.Set("Authorization", "Bearer "+b.Access)
		c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(f.http.URL, "http")+fmt.Sprintf("/v1/ws?cursor=%d", cursor), &websocket.DialOptions{HTTPHeader: header})
		if err != nil {
			t.Fatal(err)
		}
		var ready map[string]any
		if wsjson.Read(ctx, c, &ready) != nil || ready["type"] != "ready" {
			t.Fatal("websocket handshake")
		}
		return c
	}
	c := dial(0)
	var batch struct {
		Cursor int64   `json:"cursor"`
		Events []event `json:"events"`
	}
	if wsjson.Read(ctx, c, &batch) != nil || len(batch.Events) == 0 {
		t.Fatal("missing initial events")
	}
	cursor := batch.Cursor
	if wsjson.Write(ctx, c, map[string]any{"type": "ack", "cursor": cursor}) != nil {
		t.Fatal("ack")
	}
	c.CloseNow()
	f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"offline message"}`)}, 201)
	c = dial(cursor)
	defer c.CloseNow()
	if wsjson.Read(ctx, c, &batch) != nil || len(batch.Events) == 0 || batch.Cursor <= cursor {
		t.Fatal("offline resume")
	}
	for _, e := range batch.Events {
		if e.Sequence <= cursor {
			t.Fatal("replayed acknowledged event")
		}
	}
}
func TestIntegrationConcurrencyAndRateLimit(t *testing.T) {
	f := setup(t)
	a := f.register("a@example.com")
	group := f.conversation(a, "group")
	payload, _ := json.Marshal(messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"exactly once"}`)})
	var wg sync.WaitGroup
	statuses := make(chan int, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req, _ := http.NewRequest("POST", f.http.URL+"/v1/conversations/"+group+"/messages", bytes.NewReader(payload))
			req.Header.Set("Authorization", "Bearer "+a.Access)
			res, e := f.http.Client().Do(req)
			if e != nil {
				statuses <- 0
				return
			}
			defer res.Body.Close()
			statuses <- res.StatusCode
		}()
	}
	wg.Wait()
	close(statuses)
	created := 0
	for status := range statuses {
		if status == 201 {
			created++
		} else if status != 200 {
			t.Fatalf("concurrent send status %d", status)
		}
	}
	if created != 1 {
		t.Fatal("duplicate insertion")
	}
	data := f.request("GET", "/v1/conversations/"+group+"/messages", a.Access, nil, 200)
	var messages []any
	_ = json.Unmarshal(data, &messages)
	if len(messages) != 1 {
		t.Fatal("duplicate messages")
	}
	for i := 0; i < 5; i++ {
		f.request("POST", "/v1/auth/login", "", map[string]string{"email": "a@example.com", "password": "wrong password", "device_id": a.Device, "device_name": "test"}, 401)
	}
	f.request("POST", "/v1/auth/login", "", map[string]string{"email": "a@example.com", "password": "strong test password", "device_id": a.Device, "device_name": "test"}, 401)
	for i := 0; i < 20; i++ {
		f.s.limiter.Allow("auth:127.0.0.1", 20, time.Minute)
	}
	f.request("POST", "/v1/auth/login", "", map[string]string{}, 429)
}
func TestIntegrationAdminTOTP(t *testing.T) {
	f := setup(t)
	a := f.register("admin@example.com")
	if _, err := f.db.Exec(context.Background(), `UPDATE users SET admin=true WHERE id=$1`, a.User); err != nil {
		t.Fatal(err)
	}
	f.request("GET", "/v1/admin/dashboard", a.Access, nil, 403)
	code, err := totp.GenerateCode(f.totp, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	data := f.request("POST", "/v1/admin/unlock", a.Access, map[string]string{"code": code}, 200)
	var grant map[string]any
	_ = json.Unmarshal(data, &grant)
	f.request("POST", "/v1/admin/unlock", a.Access, map[string]string{"code": code}, 401)
	req, _ := http.NewRequest("GET", f.http.URL+"/v1/admin/dashboard", nil)
	req.Header.Set("Authorization", "Bearer "+a.Access)
	req.Header.Set("X-VYRN-Admin-Token", grant["admin_token"].(string))
	res, err := f.http.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("admin status %d", res.StatusCode)
	}
}
