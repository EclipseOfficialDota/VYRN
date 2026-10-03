package api

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/google/uuid"
)

func TestProfileChangesEmitScopedAtomicEvents(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	a, b, c, d := f.register("profile-events-a@test.invalid"), f.register("profile-events-b@test.invalid"), f.register("profile-events-c@test.invalid"), f.register("profile-events-disabled@test.invalid")
	group := f.conversation(a, "group", b.User, d.User)
	f.conversation(a, "group", b.User)
	if _, err := f.db.Exec(ctx, `UPDATE users SET disabled=true WHERE id=$1`, d.User); err != nil {
		t.Fatal(err)
	}
	count := func(user string) int {
		t.Helper()
		var n int
		if err := f.db.QueryRow(ctx, `SELECT count(*) FROM events WHERE user_id=$1 AND kind='user.updated'`, user).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	change := map[string]any{"first_name": "Profile", "last_name": "Changed", "about": "visible biography"}
	f.request("PATCH", "/v1/me", a.Access, change, 200)
	if count(a.User) != 1 || count(b.User) != 1 || count(c.User) != 0 || count(d.User) != 0 {
		t.Fatal("profile events must reach owner and distinct active conversation peers only")
	}
	var body []byte
	if err := f.db.QueryRow(ctx, `SELECT body FROM events WHERE user_id=$1 AND kind='user.updated'`, b.User).Scan(&body); err != nil {
		t.Fatal(err)
	}
	var value map[string]any
	if json.Unmarshal(body, &value) != nil || len(value) != 1 || value["user_id"] != a.User {
		t.Fatal("event body must contain only public identity")
	}
	f.request("PATCH", "/v1/me", a.Access, change, 200)
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": nil}, 200)
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"first_name": ""}, 400)
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": uuid.NewString()}, 400)
	if count(a.User) != 1 || count(b.User) != 1 {
		t.Fatal("unchanged and rejected profiles must not emit events")
	}
	first, second := uuid.NewString(), uuid.NewString()
	for _, id := range []string{first, second} {
		if _, err := f.db.Exec(ctx, `INSERT INTO media(id,owner_id,storage_key,filename,content_type,size,sha256,kind,encrypted) VALUES($1::uuid,$2,$1::text,'avatar.png','image/png',1,'fixture','avatar',false)`, id, a.User); err != nil {
			t.Fatal(err)
		}
	}
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": first}, 200)
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": second}, 200)
	f.request("DELETE", "/v1/me/avatar/"+first, a.Access, nil, 409)
	if count(a.User) != 3 || count(b.User) != 3 {
		t.Fatal("replacement emits once; stale deletion emits nothing")
	}
	if _, err := f.db.Exec(ctx, `ALTER TABLE events ADD CONSTRAINT reject_profile_event CHECK (kind<>'user.updated') NOT VALID`); err != nil {
		t.Fatal(err)
	}
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"about": "must roll back"}, 500)
	f.request("DELETE", "/v1/me/avatar/"+second, a.Access, nil, 500)
	var about, avatar string
	if err := f.db.QueryRow(ctx, `SELECT about,avatar_id::text FROM users WHERE id=$1`, a.User).Scan(&about, &avatar); err != nil || about != "visible biography" || avatar != second || count(a.User) != 3 {
		t.Fatal("profile changes must roll back if event persistence fails")
	}
	if _, err := f.db.Exec(ctx, `ALTER TABLE events DROP CONSTRAINT reject_profile_event`); err != nil {
		t.Fatal(err)
	}
	f.request("DELETE", "/v1/me/avatar/"+second, a.Access, nil, 204)
	f.request("DELETE", "/v1/me/avatar/"+second, a.Access, nil, 204)
	if count(a.User) != 4 || count(b.User) != 4 {
		t.Fatal("confirmed clear emits once")
	}
	f.request("DELETE", "/v1/conversations/"+group+"/members/"+b.User, a.Access, nil, 204)
	// Remove the second shared membership too: historical peers must stop receiving events.
	if _, err := f.db.Exec(ctx, `DELETE FROM members WHERE user_id=$1`, b.User); err != nil {
		t.Fatal(err)
	}
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"about": "after removal"}, 200)
	if count(a.User) != 5 || count(b.User) != 4 || count(c.User) != 0 || count(d.User) != 0 {
		t.Fatal("former members must not receive new profile events")
	}
}
