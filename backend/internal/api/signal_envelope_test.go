package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Opaque synthetic ciphertext tests server validation, not Signal encryption.
func envelopeFixture(sender account, senderAddress string, targets map[account]string) signalEnvelope {
	out := signalEnvelope{Protocol: "signal-v2", Sender: signalSender{sender.User, sender.Device, senderAddress, 1}, Devices: map[string]signalCiphertext{}}
	for user, address := range targets {
		out.Devices[address] = signalCiphertext{user.User, user.Device, 1, 3, base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{17}, 64))}
	}
	return out
}
func TestSignalEnvelopeShape(t *testing.T) {
	sender := account{User: uuid.NewString(), Device: uuid.NewString()}
	peer := account{User: uuid.NewString(), Device: uuid.NewString()}
	envelope := envelopeFixture(sender, uuid.NewString(), map[account]string{peer: uuid.NewString()})
	encoded, _ := json.Marshal(envelope)
	if !validPayload(encoded, true) {
		t.Fatal("valid envelope rejected")
	}
	for _, mutate := range []func(map[string]any){
		func(v map[string]any) { v["protocol"] = "signal-v1" },
		func(v map[string]any) { v["text"] = "plaintext" },
		func(v map[string]any) { v["sender"].(map[string]any)["version"] = 1.5 },
		func(v map[string]any) { v["sender"].(map[string]any)["signal_address_id"] = "bad" },
		func(v map[string]any) { v["devices"] = map[string]any{} },
		func(v map[string]any) {
			for _, raw := range v["devices"].(map[string]any) {
				raw.(map[string]any)["type"] = 1
			}
		},
		func(v map[string]any) {
			for _, raw := range v["devices"].(map[string]any) {
				raw.(map[string]any)["ciphertext"] = "not ciphertext"
			}
		},
		func(v map[string]any) {
			for _, raw := range v["devices"].(map[string]any) {
				raw.(map[string]any)["ciphertext"] = base64.StdEncoding.EncodeToString([]byte("short"))
			}
		},
		func(v map[string]any) {
			for _, raw := range v["devices"].(map[string]any) {
				cipher := raw.(map[string]any)
				cipher["ciphertext"] = cipher["ciphertext"].(string) + "\n"
			}
		},
	} {
		var value map[string]any
		_ = json.Unmarshal(encoded, &value)
		mutate(value)
		bad, _ := json.Marshal(value)
		if validPayload(bad, true) {
			t.Fatal("invalid transport shape accepted")
		}
	}
	if validPayload(append(encoded, []byte(`{}`)...), true) {
		t.Fatal("trailing JSON accepted")
	}
}

func publishFixtureKeys(f *fixture, a account, seed byte) string {
	f.t.Helper()
	var response struct {
		Address string `json:"signal_address_id"`
	}
	if json.Unmarshal(f.request("PUT", "/v1/me/device-keys", a.Access, keyFixture(0, seed, 4), 200), &response) != nil || !validID(response.Address) {
		f.t.Fatal("key publication")
	}
	return response.Address
}
func loginFixtureDevice(f *fixture, a account, email, device string) account {
	f.t.Helper()
	var tokens tokenPair
	if json.Unmarshal(f.request("POST", "/v1/auth/login", "", map[string]string{"email": email, "password": "strong test password", "device_id": device, "device_name": "Signal fixture"}, 200), &tokens) != nil {
		f.t.Fatal("device login")
	}
	return account{User: tokens.UserID, Access: tokens.Access, Refresh: tokens.Refresh, Device: device}
}
func TestSignalEnvelopeFanout(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	a := f.register("fanout-a@example.com")
	b := f.register("fanout-b@example.com")
	other := f.register("fanout-other@example.com")
	conversation := f.conversation(a, "private", b.User)
	path := "/v1/conversations/" + conversation + "/messages"
	aa := publishFixtureKeys(f, a, 11)
	ba := publishFixtureKeys(f, b, 12)
	envelope := envelopeFixture(a, aa, map[account]string{b: ba})
	input := func(e signalEnvelope) messageInput {
		payload, _ := json.Marshal(e)
		return messageInput{ClientID: uuid.NewString(), Payload: payload, Encrypted: true}
	}
	message := input(envelope)
	var accepted struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(f.request("POST", path, a.Access, message, 201), &accepted) != nil {
		t.Fatal("accepted message")
	}
	f.request("POST", path, other.Access, message, 404)
	f.request("GET", "/v1/messages/"+accepted.ID, other.Access, nil, 404)
	f.request("POST", path, a.Access, message, 200)
	assertNoMutation := func() {
		var n int
		if err := f.db.QueryRow(ctx, `SELECT count(*) FROM messages WHERE conversation_id=$1`, conversation).Scan(&n); err != nil || n != 1 {
			t.Fatal("rejected envelope mutated messages")
		}
		if err := f.db.QueryRow(ctx, `SELECT count(*) FROM events WHERE kind='message.created' AND body->>'conversation_id'=$1`, conversation).Scan(&n); err != nil || n != 2 {
			t.Fatal("rejected envelope emitted events")
		}
	}
	for _, change := range []func(*signalEnvelope){
		func(e *signalEnvelope) { e.Sender.User = b.User },
		func(e *signalEnvelope) { e.Sender.Device = b.Device },
		func(e *signalEnvelope) { e.Sender.Address = uuid.NewString() },
		func(e *signalEnvelope) { e.Sender.Version = 2 },
		func(e *signalEnvelope) { c := e.Devices[ba]; c.Version = 2; e.Devices[ba] = c },
		func(e *signalEnvelope) { c := e.Devices[ba]; c.User = other.User; e.Devices[ba] = c },
		func(e *signalEnvelope) { c := e.Devices[ba]; c.Device = other.Device; e.Devices[ba] = c },
		func(e *signalEnvelope) { c := e.Devices[ba]; delete(e.Devices, ba); e.Devices[uuid.NewString()] = c },
		func(e *signalEnvelope) {
			e.Devices[aa] = signalCiphertext{a.User, a.Device, 1, 3, base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{17}, 64))}
		},
	} {
		bad := envelopeFixture(a, aa, map[account]string{b: ba})
		change(&bad)
		f.request("POST", path, a.Access, input(bad), 409)
		assertNoMutation()
	}
	// Two accounts can legitimately use the same physical device UUID: address UUIDs stay distinct.
	b2 := loginFixtureDevice(f, b, "fanout-b@example.com", a.Device)
	f.request("POST", path, a.Access, input(envelope), 409)
	b2a := publishFixtureKeys(f, b2, 13)
	if b2a == aa {
		t.Fatal("cross-account address collision")
	}
	f.request("POST", path, a.Access, input(envelope), 409)
	expanded := envelopeFixture(a, aa, map[account]string{b: ba, b2: b2a})
	fresh := input(expanded)
	f.request("POST", path, a.Access, fresh, 201)
	// Add an unprepared sender-side device: it cannot silently be omitted either.
	a2 := loginFixtureDevice(f, a, "fanout-a@example.com", uuid.NewString())
	f.request("POST", path, a.Access, input(expanded), 409)
	a2a := publishFixtureKeys(f, a2, 14)
	all := envelopeFixture(a, aa, map[account]string{b: ba, b2: b2a, a2: a2a})
	f.request("POST", path, a.Access, input(all), 201)
	// Rotation rejects new stale sends/edits, but an already committed exact retry keeps its ID.
	rotation := keyFixture(1, 12, 0)
	rotation.SignedPreKey.ID = 2
	rotation.KyberPreKey.ID = 2
	f.request("PUT", "/v1/me/device-keys", b.Access, rotation, 200)
	f.request("POST", path, a.Access, fresh, 200)
	f.request("POST", path, a.Access, input(all), 409)
	current := all
	c := current.Devices[ba]
	c.Version = 2
	current.Devices[ba] = c
	badEdit, _ := json.Marshal(envelope)
	f.request("PATCH", "/v1/messages/"+accepted.ID, a.Access, map[string]any{"payload": json.RawMessage(badEdit), "revision": 1, "encrypted": true}, 409)
	updated, _ := json.Marshal(current)
	f.request("PATCH", "/v1/messages/"+accepted.ID, a.Access, map[string]any{"payload": json.RawMessage(updated), "revision": 1, "encrypted": true}, 204)
	f.request("PATCH", "/v1/messages/"+accepted.ID, a.Access, map[string]any{"payload": json.RawMessage(updated), "revision": 1, "encrypted": true}, 409)
	// Edits from another owning device bind the new producing device in the stored message.
	fromOther := envelopeFixture(a2, a2a, map[account]string{a: aa, b: ba, b2: b2a})
	c = fromOther.Devices[ba]
	c.Version = 2
	fromOther.Devices[ba] = c
	otherEdit, _ := json.Marshal(fromOther)
	f.request("PATCH", "/v1/messages/"+accepted.ID, a2.Access, map[string]any{"payload": json.RawMessage(otherEdit), "revision": 2, "encrypted": true}, 204)
	var read struct {
		Device   string `json:"sender_device"`
		Revision int    `json:"revision"`
	}
	if json.Unmarshal(f.request("GET", "/v1/messages/"+accepted.ID, b.Access, nil, 200), &read) != nil || read.Device != a2.Device || read.Revision != 3 {
		t.Fatal("edit producing-device provenance")
	}
	f.request("PUT", "/v1/blocks/"+b.User, a.Access, nil, 204)
	f.request("PATCH", "/v1/messages/"+accepted.ID, a2.Access, map[string]any{"payload": json.RawMessage(otherEdit), "revision": 3, "encrypted": true}, 403)
	f.request("DELETE", "/v1/blocks/"+b.User, a.Access, nil, 204)
	f.request("POST", "/v1/auth/logout", b2.Access, map[string]any{}, 204)
	f.request("POST", path, a.Access, input(current), 409)
	delete(current.Devices, b2a)
	f.request("POST", path, a.Access, input(current), 201)
	f.request("POST", "/v1/auth/logout", b.Access, map[string]any{}, 204)
	f.request("POST", path, a.Access, message, 200) // committed retry remains recoverable with no live recipient
	f.request("POST", path, a.Access, input(current), 409)
}

func TestSignalEnvelopeRechecksAfterLock(t *testing.T) {
	for _, kind := range []string{"rotation", "new_device", "revoked_sender"} {
		t.Run(kind, func(t *testing.T) {
			f := setup(t)
			a := f.register("wait-a@example.com")
			b := f.register("wait-b@example.com")
			conversation := f.conversation(a, "private", b.User)
			aa := publishFixtureKeys(f, a, 21)
			ba := publishFixtureKeys(f, b, 22)
			envelope := envelopeFixture(a, aa, map[account]string{b: ba})
			payload, _ := json.Marshal(envelope)
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			tx, err := f.db.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			var locker int
			if err = tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&locker); err != nil {
				t.Fatal(err)
			}
			var locked string
			switch kind {
			case "rotation":
				err = tx.QueryRow(ctx, `UPDATE device_keys SET version=version+1 WHERE user_id=$1 AND device_id=$2 RETURNING signal_address_id::text`, b.User, b.Device).Scan(&locked)
			case "new_device":
				err = tx.QueryRow(ctx, `SELECT id::text FROM users WHERE id=$1 FOR UPDATE`, b.User).Scan(&locked)
			case "revoked_sender":
				err = tx.QueryRow(ctx, `SELECT id::text FROM conversations WHERE id=$1 FOR UPDATE`, conversation).Scan(&locked)
			}
			if err != nil {
				t.Fatal(err)
			}
			outcome := make(chan int, 1)
			go func() {
				body, _ := json.Marshal(messageInput{ClientID: uuid.NewString(), Payload: payload, Encrypted: true})
				request, _ := http.NewRequestWithContext(ctx, "POST", f.http.URL+"/v1/conversations/"+conversation+"/messages", bytes.NewReader(body))
				request.Header.Set("Authorization", "Bearer "+a.Access)
				request.Header.Set("Content-Type", "application/json")
				response, err := f.http.Client().Do(request)
				if err != nil {
					outcome <- 0
					return
				}
				response.Body.Close()
				outcome <- response.StatusCode
			}()
			waiting := false
			deadline := time.Now().Add(5 * time.Second)
			for time.Now().Before(deadline) {
				if err = f.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE $1=ANY(pg_blocking_pids(pid)))`, locker).Scan(&waiting); err != nil {
					t.Fatal(err)
				}
				if waiting {
					break
				}
				time.Sleep(10 * time.Millisecond)
			}
			if !waiting {
				t.Fatal("request never reached controlled lock")
			}
			if kind == "new_device" {
				_, err = tx.Exec(ctx, `INSERT INTO sessions(id,user_id,device_id,device_name,access_hash,refresh_hash,access_expires,refresh_expires) VALUES($1,$2,$3,'fixture',$4,$5,now()+interval '15 minutes',now()+interval '1 day')`, uuid.NewString(), b.User, uuid.NewString(), bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32))
			}
			if kind == "revoked_sender" {
				_, err = tx.Exec(ctx, `UPDATE sessions SET revoked_at=now() WHERE user_id=$1`, a.User)
			}
			if err != nil {
				t.Fatal(err)
			}
			if err = tx.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			want := 409
			if kind == "revoked_sender" {
				want = 401
			}
			select {
			case got := <-outcome:
				if got != want {
					t.Fatalf("status %d, want %d", got, want)
				}
			case <-ctx.Done():
				t.Fatal("send did not finish")
			}
			var n int
			if err = f.db.QueryRow(ctx, `SELECT count(*) FROM messages WHERE conversation_id=$1`, conversation).Scan(&n); err != nil || n != 0 {
				t.Fatal("rejected waiting send persisted")
			}
		})
	}
}

func waitSignalBlock(t *testing.T, f *fixture, ctx context.Context, blocker int) int {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiter int
		err := f.db.QueryRow(ctx, `SELECT pid FROM pg_stat_activity WHERE application_name=$2 AND $1=ANY(pg_blocking_pids(pid)) ORDER BY pid LIMIT 1`, blocker, f.db.Config().ConnConfig.RuntimeParams["application_name"]).Scan(&waiter)
		if err == nil {
			return waiter
		}
		if err != pgx.ErrNoRows {
			t.Fatal(err)
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("expected database lock wait did not occur")
	return 0
}

func TestSignalEnvelopeLocksRosterUntilCommit(t *testing.T) {
	for _, kind := range []string{"rotation", "new_device", "recipient_revocation"} {
		t.Run(kind, func(t *testing.T) {
			f := setup(t)
			a := f.register("linear-a@example.com")
			b := f.register("linear-b@example.com")
			conversation := f.conversation(a, "private", b.User)
			aa := publishFixtureKeys(f, a, 70)
			ba := publishFixtureKeys(f, b, 71)
			payload, _ := json.Marshal(envelopeFixture(a, aa, map[account]string{b: ba}))
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			tx, err := f.db.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			var locker int
			if err = tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&locker); err != nil {
				t.Fatal(err)
			}
			if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(86142002)`); err != nil {
				t.Fatal(err)
			}
			start := func(method, path, token string, body any) <-chan int {
				result := make(chan int, 1)
				go func() {
					data, _ := json.Marshal(body)
					request, _ := http.NewRequestWithContext(ctx, method, f.http.URL+path, bytes.NewReader(data))
					request.Header.Set("Content-Type", "application/json")
					request.Header.Set("Authorization", "Bearer "+token)
					response, err := f.http.Client().Do(request)
					if err != nil {
						result <- 0
						return
					}
					response.Body.Close()
					result <- response.StatusCode
				}()
				return result
			}
			sent := start("POST", "/v1/conversations/"+conversation+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: payload, Encrypted: true})
			senderPID := waitSignalBlock(t, f, ctx, locker) // message passed validation and is waiting to emit before commit
			var changed <-chan int
			want := 200
			switch kind {
			case "rotation":
				rotation := keyFixture(1, 71, 0)
				rotation.SignedPreKey.ID = 2
				rotation.KyberPreKey.ID = 2
				changed = start("PUT", "/v1/me/device-keys", b.Access, rotation)
			case "new_device":
				changed = start("POST", "/v1/auth/login", "", map[string]string{"email": "linear-b@example.com", "password": "strong test password", "device_id": uuid.NewString(), "device_name": "Concurrent join"})
			case "recipient_revocation":
				changed = start("POST", "/v1/auth/logout", b.Access, map[string]any{})
				want = 204
			}
			waitSignalBlock(t, f, ctx, senderPID) // lifecycle mutation must wait for the validated message transaction
			if err = tx.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case got := <-sent:
				if got != 201 {
					t.Fatalf("send status %d", got)
				}
			case <-ctx.Done():
				t.Fatal("send did not commit")
			}
			select {
			case got := <-changed:
				if got != want {
					t.Fatalf("lifecycle status %d, want %d", got, want)
				}
			case <-ctx.Done():
				t.Fatal("lifecycle mutation did not finish")
			}
			var count int
			if err = f.db.QueryRow(ctx, `SELECT count(*) FROM messages WHERE conversation_id=$1`, conversation).Scan(&count); err != nil || count != 1 {
				t.Fatal("committed message missing")
			}
		})
	}
}
