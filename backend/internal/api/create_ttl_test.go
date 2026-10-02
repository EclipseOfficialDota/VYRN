package api

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/google/uuid"
)

func TestGroupCreationTTLAtomicity(t *testing.T) {
	f := setup(t)
	a, b := f.register("ttl-owner@test.invalid"), f.register("ttl-member@test.invalid")
	ctx := context.Background()
	for _, seconds := range []int{0, 1, 31536000} {
		data := f.request("POST", "/v1/conversations", a.Access, map[string]any{"kind": "group", "title": "TTL group", "members": []string{b.User}, "disappearing_seconds": seconds}, 201)
		var created struct {
			ID      string `json:"id"`
			Seconds int    `json:"disappearing_seconds"`
		}
		if json.Unmarshal(data, &created) != nil || created.Seconds != seconds {
			t.Fatal("creation acknowledgement lost TTL")
		}
		var stored, members, events int
		if err := f.db.QueryRow(ctx, `SELECT disappearing_seconds,(SELECT count(*) FROM members WHERE conversation_id=c.id),(SELECT count(*) FROM events WHERE kind='conversation.created' AND body->>'id'=c.id::text AND (body->>'disappearing_seconds')::int=$2) FROM conversations c WHERE id=$1`, created.ID, seconds).Scan(&stored, &members, &events); err != nil || stored != seconds || members != 2 || events != 2 {
			t.Fatal("conversation, membership and recipient events must commit with the same TTL")
		}
		f.request("POST", "/v1/conversations/"+created.ID+"/messages", a.Access, map[string]any{"client_id": uuid.NewString(), "payload": map[string]string{"text": "First message"}, "encrypted": false}, 201)
		var expiresCorrectly bool
		if err := f.db.QueryRow(ctx, `SELECT CASE WHEN $2::int=0 THEN expires_at IS NULL ELSE expires_at-created_at=$2*interval '1 second' END FROM messages WHERE conversation_id=$1`, created.ID, seconds).Scan(&expiresCorrectly); err != nil || !expiresCorrectly {
			t.Fatal("first send must use the creation-time TTL")
		}
	}
	for _, seconds := range []int{-1, 31536001} {
		f.request("POST", "/v1/conversations", a.Access, map[string]any{"kind": "group", "title": "Invalid TTL", "disappearing_seconds": seconds}, 400)
	}
	f.request("POST", "/v1/conversations", a.Access, map[string]any{"kind": "private", "members": []string{b.User}, "disappearing_seconds": 60}, 400)
	f.request("POST", "/v1/conversations", a.Access, map[string]any{"kind": "group", "title": "Invalid member", "members": []string{uuid.NewString()}, "disappearing_seconds": 60}, 400)
	var count int
	if err := f.db.QueryRow(ctx, "SELECT count(*) FROM conversations").Scan(&count); err != nil || count != 3 {
		t.Fatal("invalid creation must not leave a conversation behind")
	}
}
