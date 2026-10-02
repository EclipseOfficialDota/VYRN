package api

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestConversationDetailsAuthorizationAndSnapshot(t *testing.T) {
	f := setup(t)
	a, b, outsider := f.register("details-owner@test.invalid"), f.register("details-admin@test.invalid"), f.register("details-outsider@test.invalid")
	group := f.conversation(a, "group", b.User)
	f.request("POST", "/v1/conversations/"+group+"/members", a.Access, map[string]string{"user_id": b.User, "role": "admin"}, 204)
	f.request("PATCH", "/v1/conversations/"+group, a.Access, map[string]any{"title": "گروه", "disappearing_seconds": 86400}, 204)
	f.request("GET", "/v1/conversations/"+group, "", nil, 401)
	f.request("GET", "/v1/conversations/"+group, outsider.Access, nil, 404)
	f.request("GET", "/v1/conversations/invalid", a.Access, nil, 404)
	var snapshot struct {
		ID, Title, Role string
		Seconds         int `json:"disappearing_seconds"`
		Members         []struct {
			ID, Role string
			Joined   time.Time `json:"joined_at"`
		}
	}
	data := f.request("GET", "/v1/conversations/"+group, b.Access, nil, 200)
	if json.Unmarshal(data, &snapshot) != nil || snapshot.ID != group || snapshot.Title != "گروه" || snapshot.Role != "admin" || snapshot.Seconds != 86400 || len(snapshot.Members) != 2 {
		t.Fatal("member-authorized snapshot lost conversation fields")
	}
	for _, member := range snapshot.Members {
		want := "owner"
		if member.ID == b.User {
			want = "admin"
		} else if member.ID != a.User {
			t.Fatal("snapshot exposed an unrelated user")
		}
		if member.Role != want || member.Joined.IsZero() || member.Joined.After(time.Now()) {
			t.Fatal("snapshot lost authoritative role or membership timestamp")
		}
	}
	// Targeted lookup must remain available when the old group falls outside list pagination.
	if _, err := f.db.Exec(context.Background(), `WITH inserted AS (INSERT INTO conversations(id,kind,title,owner_id) SELECT gen_random_uuid(),'group','Newer group',$1 FROM generate_series(1,501) RETURNING id) INSERT INTO members(conversation_id,user_id,role) SELECT id,$1,'owner' FROM inserted`, a.User); err != nil {
		t.Fatal(err)
	}
	var listed []struct{ ID string }
	if json.Unmarshal(f.request("GET", "/v1/conversations", a.Access, nil, 200), &listed) != nil || len(listed) != 500 {
		t.Fatal("pagination fixture did not reach the list bound")
	}
	for _, item := range listed {
		if item.ID == group {
			t.Fatal("old group must be outside the bounded list in this fixture")
		}
	}
	f.request("GET", "/v1/conversations/"+group, a.Access, nil, 200)
	f.request("DELETE", "/v1/conversations/"+group+"/members/"+b.User, a.Access, nil, 204)
	f.request("GET", "/v1/conversations/"+group, b.Access, nil, 404)
}
