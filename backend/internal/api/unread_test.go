package api

import (
	"context"
	"encoding/json"
	"github.com/google/uuid"
	"testing"
)

func TestIntegrationConversationUnread(t *testing.T) {
	f := setup(t)
	a := f.register("unread-a@example.com")
	b := f.register("unread-b@example.com")
	outsider := f.register("unread-c@example.com")
	group := f.conversation(a, "group", b.User)
	count := func(who account, want int) {
		t.Helper()
		if want < 0 {
			f.request("GET", "/v1/conversations/"+group, who.Access, nil, 404)
		} else {
			var snapshot struct {
				Unread *int `json:"unread_count"`
			}
			if err := json.Unmarshal(f.request("GET", "/v1/conversations/"+group, who.Access, nil, 200), &snapshot); err != nil || snapshot.Unread == nil || *snapshot.Unread != want {
				t.Fatal("conversation snapshot unread count mismatch")
			}
		}
		var rows []struct {
			ID     string `json:"id"`
			Unread int    `json:"unread_count"`
		}
		if err := json.Unmarshal(f.request("GET", "/v1/conversations", who.Access, nil, 200), &rows); err != nil {
			t.Fatal(err)
		}
		for _, row := range rows {
			if row.ID == group {
				if row.Unread != want {
					t.Fatalf("unread count got %d want %d", row.Unread, want)
				}
				return
			}
		}
		if want != -1 {
			t.Fatal("conversation missing")
		}
	}
	send := func(who account) string {
		t.Helper()
		var result struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", who.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"unread fixture"}`)}, 201), &result); err != nil {
			t.Fatal(err)
		}
		return result.ID
	}
	id := send(a)
	count(a, 0)
	count(b, 1)
	count(outsider, -1)
	f.request("PUT", "/v1/messages/"+id+"/receipt", b.Access, map[string]bool{"read": false}, 204)
	count(b, 1)
	f.request("PUT", "/v1/messages/"+id+"/receipt", b.Access, map[string]bool{"read": true}, 204)
	count(b, 0)
	f.request("PUT", "/v1/messages/"+id+"/receipt", b.Access, map[string]bool{"read": false}, 204)
	count(b, 0)
	deleted := send(a)
	count(b, 1)
	f.request("DELETE", "/v1/messages/"+deleted, a.Access, nil, 204)
	count(b, 0)
	expired := send(a)
	count(b, 1)
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET expires_at=now()-interval '1 second' WHERE id=$1`, expired); err != nil {
		t.Fatal(err)
	}
	count(b, 0)
	send(b)
	count(b, 0)
	count(a, 1)
	count(a, 1)
	var firstUnread string
	for i := 0; i < 101; i++ {
		id := send(a)
		if i == 0 {
			firstUnread = id
		}
	}
	count(b, 101)
	var firstOrdinal int
	if err := f.db.QueryRow(context.Background(), `SELECT ordinal FROM messages WHERE id=$1`, firstUnread).Scan(&firstOrdinal); err != nil {
		t.Fatal(err)
	}
	f.request("PUT", "/v1/conversations/"+group+"/read", b.Access, map[string]int{"max_id": firstOrdinal}, 204)
	count(b, 100)
	f.request("PUT", "/v1/conversations/"+group+"/read", outsider.Access, map[string]int{"max_id": 2147483647}, 404)
	f.request("PUT", "/v1/conversations/"+group+"/read", b.Access, map[string]int{"max_id": 0}, 400)
	f.request("PUT", "/v1/conversations/"+group+"/read", b.Access, map[string]int{"max_id": 2147483647}, 204)
	count(b, 0)
	f.request("PUT", "/v1/conversations/"+group+"/read", b.Access, map[string]int{"max_id": 2147483647}, 204)
	send(a)
	count(b, 1)
	count(a, 1)
}
