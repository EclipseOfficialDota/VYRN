package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"testing"

	"github.com/google/uuid"
)

// Artificial wire-shape fixtures, not valid cryptographic keys/signatures.
// Real PQXDH and signature verification are tested on Android with libsignal.
func keyFixture(version int, seed byte, count int) keyPublication {
	encoded := func(size int, prefix byte) string {
		b := bytes.Repeat([]byte{seed}, size)
		b[0] = prefix
		return base64.StdEncoding.EncodeToString(b)
	}
	in := keyPublication{Version: &version, RegistrationID: 123, IdentityKey: encoded(33, 5), SignedPreKey: publicPreKey{ID: 1, PublicKey: encoded(33, 5), Signature: encoded(64, seed)}, KyberPreKey: publicPreKey{ID: 1, PublicKey: encoded(1569, 8), Signature: encoded(64, seed)}}
	for i := 0; i < count; i++ {
		in.PreKeys = append(in.PreKeys, publicPreKey{ID: i + 10, PublicKey: encoded(33, 5)})
	}
	return in
}

func TestDeviceKeyPublicationAndClaims(t *testing.T) {
	f := setup(t)
	alice := f.register("keys-alice@example.com")
	bob := f.register("keys-bob@example.com")
	outsider := f.register("keys-outsider@example.com")
	f.conversation(alice, "private", bob.User)
	publication := keyFixture(0, 17, 8)
	f.request("PUT", "/v1/me/device-keys", "", publication, 401)
	bad := publication
	bad.IdentityKey = "bad"
	f.request("PUT", "/v1/me/device-keys", bob.Access, bad, 400)
	bad = publication
	bad.Version = nil
	f.request("PUT", "/v1/me/device-keys", bob.Access, bad, 400)
	bad = publication
	bad.PreKeys = append(append([]publicPreKey{}, publication.PreKeys...), publication.PreKeys[0])
	f.request("PUT", "/v1/me/device-keys", bob.Access, bad, 400)
	var published struct {
		Device, Address    string
		Version, Available int
	}
	decodePublication := func(data []byte) {
		var v struct {
			Device    string `json:"device_id"`
			Address   string `json:"signal_address_id"`
			Version   int    `json:"version"`
			Available int    `json:"prekeys_available"`
		}
		if json.Unmarshal(data, &v) != nil {
			t.Fatal("publication response")
		}
		published.Device, published.Address, published.Version, published.Available = v.Device, v.Address, v.Version, v.Available
	}
	eventCount := func(user string) int {
		var n int
		if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM events WHERE user_id=$1 AND kind='device.keys.updated'`, user).Scan(&n); err != nil {
			t.Fatal("key event count")
		}
		return n
	}
	decodePublication(f.request("PUT", "/v1/me/device-keys", bob.Access, publication, 200))
	if published.Device != bob.Device || !validID(published.Address) || published.Version != 1 || published.Available != 8 {
		t.Fatal("publication binding")
	}
	address := published.Address
	decodePublication(f.request("PUT", "/v1/me/device-keys", bob.Access, publication, 200))
	if published.Address != address || published.Version != 1 || published.Available != 8 {
		t.Fatal("publication replay")
	}
	if eventCount(bob.User) != 1 || eventCount(alice.User) != 1 || eventCount(outsider.User) != 0 {
		t.Fatal("key event scope or duplicate publication")
	}
	f.request("GET", "/v1/users/"+bob.User+"/device-keys", outsider.Access, nil, 404)
	claimPath := "/v1/users/" + bob.User + "/device-keys/" + bob.Device + "/claim"
	f.request("POST", claimPath, outsider.Access, map[string]int{"expected_version": 1}, 404)
	f.request("POST", claimPath, alice.Access, map[string]int{"expected_version": 2}, 409)
	// Eight real concurrent HTTP claims must consume eight distinct keys.
	var wg sync.WaitGroup
	ids := make(chan int, 8)
	failures := make(chan bool, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req, _ := http.NewRequest("POST", f.http.URL+claimPath, bytes.NewBufferString(`{"expected_version":1}`))
			req.Header.Set("Authorization", "Bearer "+alice.Access)
			req.Header.Set("Content-Type", "application/json")
			res, err := f.http.Client().Do(req)
			if err != nil {
				failures <- true
				return
			}
			defer res.Body.Close()
			data, _ := io.ReadAll(res.Body)
			var v struct {
				Key     *publicPreKey `json:"prekey"`
				Address string        `json:"signal_address_id"`
			}
			if res.StatusCode != 200 || json.Unmarshal(data, &v) != nil || v.Key == nil || v.Address != address {
				failures <- true
				return
			}
			ids <- v.Key.ID
		}()
	}
	wg.Wait()
	close(ids)
	close(failures)
	if len(failures) != 0 {
		t.Fatal("concurrent claims")
	}
	seen := map[int]bool{}
	for id := range ids {
		if seen[id] {
			t.Fatal("prekey claimed twice")
		}
		seen[id] = true
	}
	if len(seen) != 8 {
		t.Fatal("missing claim")
	}
	decodePublication(f.request("PUT", "/v1/me/device-keys", bob.Access, publication, 200))
	if published.Available != 0 {
		t.Fatal("consumed keys resurrected")
	}
	var exhausted struct {
		Key *publicPreKey `json:"prekey"`
	}
	if json.Unmarshal(f.request("POST", claimPath, alice.Access, map[string]int{"expected_version": 1}, 200), &exhausted) != nil || exhausted.Key != nil {
		t.Fatal("last resort without EC prekey")
	}
	bad = keyFixture(1, 18, 0)
	f.request("PUT", "/v1/me/device-keys", bob.Access, bad, 409)
	bad = publication
	v := 1
	bad.Version = &v
	bad.PreKeys = []publicPreKey{{ID: 10, PublicKey: keyFixture(0, 19, 1).IdentityKey}}
	f.request("PUT", "/v1/me/device-keys", bob.Access, bad, 409)
	var count int
	if f.db.QueryRow(context.Background(), `SELECT count(*) FROM one_time_prekeys WHERE user_id=$1 AND claimed_at IS NULL`, bob.User).Scan(&count) != nil || count != 0 {
		t.Fatal("rejected mutation rollback")
	}
	rotated := publication
	rotated.Version = &v
	rotated.PreKeys = nil
	rotated.SignedPreKey.ID = 2
	rotated.KyberPreKey.ID = 2
	decodePublication(f.request("PUT", "/v1/me/device-keys", bob.Access, rotated, 200))
	if published.Version != 2 || published.Address != address {
		t.Fatal("rotation address/version")
	}
	f.request("PUT", "/v1/me/device-keys", bob.Access, publication, 409)
	f.request("POST", claimPath, alice.Access, map[string]int{"expected_version": 1}, 409)
	f.request("POST", claimPath, alice.Access, map[string]int{"expected_version": 2}, 200)
	rollback := publication
	two := 2
	rollback.Version = &two
	f.request("PUT", "/v1/me/device-keys", bob.Access, rollback, 409)
	// An old populated prekey row predating the hash ledger cannot be overwritten.
	legacyPublic := keyFixture(0, 20, 0).IdentityKey
	if _, err := f.db.Exec(context.Background(), `INSERT INTO one_time_prekeys(user_id,device_id,key_id,public_key) VALUES($1,$2,30,$3)`, bob.User, bob.Device, legacyPublic); err != nil {
		t.Fatal("legacy key fixture")
	}
	legacyConflict := rotated
	legacyConflict.Version = &two
	legacyConflict.PreKeys = []publicPreKey{{ID: 30, PublicKey: publication.IdentityKey}}
	f.request("PUT", "/v1/me/device-keys", bob.Access, legacyConflict, 409)
	if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM device_key_history WHERE user_id=$1 AND device_id=$2 AND kind='ec' AND key_id=30`, bob.User, bob.Device).Scan(&count); err != nil || count != 0 {
		t.Fatal("legacy conflict ledger rollback")
	}
	if eventCount(bob.User) != 2 || eventCount(alice.User) != 2 {
		t.Fatal("rejected key mutation emitted an event")
	}
	// A logged-in but unprepared device must appear in the roster.
	newDevice := uuid.NewString()
	data := f.request("POST", "/v1/auth/login", "", map[string]string{"email": "keys-bob@example.com", "password": "strong test password", "device_id": newDevice, "device_name": "New device"}, 200)
	var pair tokenPair
	if json.Unmarshal(data, &pair) != nil {
		t.Fatal("second session")
	}
	var roster struct {
		Devices []struct {
			ID    string `json:"device_id"`
			Ready bool   `json:"ready"`
		} `json:"devices"`
	}
	if json.Unmarshal(f.request("GET", "/v1/users/"+bob.User+"/device-keys", alice.Access, nil, 200), &roster) != nil || len(roster.Devices) != 2 {
		t.Fatal("active roster")
	}
	for _, d := range roster.Devices {
		if d.Ready != (d.ID == bob.Device) {
			t.Fatal("unprepared device readiness")
		}
	}
	f.request("PUT", "/v1/blocks/"+bob.User, alice.Access, nil, 204)
	blockedRotation := rotated
	blockedRotation.Version = &two
	blockedRotation.SignedPreKey.ID = 3
	blockedRotation.KyberPreKey.ID = 3
	f.request("PUT", "/v1/me/device-keys", bob.Access, blockedRotation, 200)
	if eventCount(bob.User) != 3 || eventCount(alice.User) != 2 || eventCount(outsider.User) != 0 {
		t.Fatal("blocked peer key event privacy")
	}
	f.request("GET", "/v1/users/"+bob.User+"/device-keys", alice.Access, nil, 404)
	f.request("POST", claimPath, alice.Access, map[string]int{"expected_version": 2}, 404)
	f.request("DELETE", "/v1/blocks/"+bob.User, alice.Access, nil, 204)
	f.request("DELETE", "/v1/sessions/"+pair.SessionID, bob.Access, nil, 204)
	f.request("PUT", "/v1/me/device-keys", pair.Access, publication, 401)
	f.request("POST", "/v1/users/"+bob.User+"/device-keys/"+newDevice+"/claim", alice.Access, map[string]int{"expected_version": 1}, 404)
}
