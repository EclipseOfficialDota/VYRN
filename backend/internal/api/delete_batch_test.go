package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestGroupDeletionRechecksMembershipAfterLock(t *testing.T) {
	f := setup(t)
	owner := f.register("lock-owner@example.com")
	admin := f.register("lock-admin@example.com")
	group := f.conversation(owner, "group", admin.User)
	var message struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", owner.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"permission must be current"}`)}, 201), &message) != nil {
		t.Fatal("message response")
	}
	for _, removal := range []bool{false, true} {
		f.request("POST", "/v1/conversations/"+group+"/members", owner.Access, map[string]any{"user_id": admin.User, "role": "admin"}, 204)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		tx, err := f.db.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer tx.Rollback(context.Background())
		var locker int
		var locked string
		if err = tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&locker); err != nil {
			t.Fatal(err)
		}
		if err = tx.QueryRow(ctx, `SELECT id::text FROM conversations WHERE id=$1 FOR UPDATE`, group).Scan(&locked); err != nil {
			t.Fatal(err)
		}
		if removal {
			_, err = tx.Exec(ctx, `DELETE FROM members WHERE conversation_id=$1 AND user_id=$2`, group, admin.User)
		} else {
			_, err = tx.Exec(ctx, `UPDATE members SET role='member' WHERE conversation_id=$1 AND user_id=$2`, group, admin.User)
		}
		if err != nil {
			t.Fatal(err)
		}
		outcome := make(chan int, 1)
		go func() {
			body, _ := json.Marshal(map[string]any{"ids": []string{message.ID}})
			request, _ := http.NewRequestWithContext(ctx, "DELETE", f.http.URL+"/v1/conversations/"+group+"/messages", bytes.NewReader(body))
			request.Header.Set("Authorization", "Bearer "+admin.Access)
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
			t.Fatal("delete request did not wait on the held conversation lock")
		}
		if err = tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		want := 403
		if removal {
			want = 404
		}
		select {
		case status := <-outcome:
			if status != want {
				t.Fatal("used membership from before lock acquisition", status, want)
			}
		case <-ctx.Done():
			t.Fatal("delete request timed out")
		}
		var deleted bool
		if err = f.db.QueryRow(context.Background(), `SELECT deleted_at IS NOT NULL FROM messages WHERE id=$1`, message.ID).Scan(&deleted); err != nil || deleted {
			t.Fatal("stale permission deleted a message", err)
		}
	}
}

func TestIntegrationAtomicGroupDeletion(t *testing.T) {
	f := setup(t)
	owner := f.register("delete-owner@example.com")
	member := f.register("delete-member@example.com")
	outsider := f.register("delete-outsider@example.com")
	group := f.conversation(owner, "group", member.User)
	path := "/v1/conversations/" + group + "/messages"
	send := func(actor account, conversation string) string {
		var value struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(f.request("POST", "/v1/conversations/"+conversation+"/messages", actor.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"batch deletion"}`)}, 201), &value) != nil {
			t.Fatal("message response")
		}
		return value.ID
	}
	first, second := send(member, group), send(owner, group)
	batch := map[string]any{"ids": []string{first, second}}
	f.request("DELETE", path, outsider.Access, batch, 404)
	f.request("DELETE", path, member.Access, batch, 403)
	unchanged := func(ids ...string) {
		for _, id := range ids {
			var deleted bool
			var revision int
			if err := f.db.QueryRow(context.Background(), `SELECT deleted_at IS NOT NULL,revision FROM messages WHERE id=$1`, id).Scan(&deleted, &revision); err != nil || deleted || revision != 1 {
				t.Fatal("rejected batch changed a message", err)
			}
		}
	}
	unchanged(first, second)
	other := f.conversation(owner, "group", member.User)
	foreign := send(owner, other)
	f.request("DELETE", path, owner.Access, map[string]any{"ids": []string{first, foreign}}, 404)
	unchanged(first, foreign)
	f.request("DELETE", path, owner.Access, map[string]any{"ids": []string{first, uuid.NewString()}}, 404)
	unchanged(first)
	f.request("DELETE", path, owner.Access, map[string]any{"ids": []string{first, first}}, 400)
	f.request("DELETE", path, owner.Access, map[string]any{"ids": []string{}}, 400)
	many := make([]string, 101)
	for i := range many {
		many[i] = uuid.NewString()
	}
	f.request("DELETE", path, owner.Access, map[string]any{"ids": many}, 400)
	f.request("DELETE", path, member.Access, map[string]any{"ids": []string{first}}, 204)
	f.request("DELETE", path, owner.Access, batch, 204)
	var eventsBefore int
	if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM events WHERE kind='message.deleted'`).Scan(&eventsBefore); err != nil {
		t.Fatal(err)
	}
	f.request("DELETE", path, owner.Access, batch, 204)
	var eventsAfter int
	if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM events WHERE kind='message.deleted'`).Scan(&eventsAfter); err != nil || eventsAfter != eventsBefore {
		t.Fatal("retry duplicated deletion events", err)
	}
	for _, id := range []string{first, second} {
		var deleted bool
		var revision int
		var payload string
		if err := f.db.QueryRow(context.Background(), `SELECT deleted_at IS NOT NULL,revision,payload::text FROM messages WHERE id=$1`, id).Scan(&deleted, &revision, &payload); err != nil || !deleted || revision != 2 || payload != "{}" {
			t.Fatal("tombstone/retry mismatch", err)
		}
	}
	f.request("POST", "/v1/conversations/"+group+"/members", owner.Access, map[string]any{"user_id": member.User, "role": "admin"}, 204)
	third := send(owner, group)
	f.request("DELETE", path, member.Access, map[string]any{"ids": []string{third}}, 204)
	fourth := send(member, group)
	f.request("DELETE", "/v1/conversations/"+group+"/members/"+member.User, owner.Access, nil, 204)
	f.request("DELETE", path, member.Access, map[string]any{"ids": []string{fourth}}, 404)
	unchanged(fourth)
	private := f.conversation(owner, "private", outsider.User)
	f.request("DELETE", "/v1/conversations/"+private+"/messages", owner.Access, map[string]any{"ids": []string{foreign}}, 404)
}

func TestLiveHistoryFiltersBeforePagination(t *testing.T) {
	f := setup(t)
	owner := f.register("live-owner@example.com")
	outsider := f.register("live-outsider@example.com")
	group := f.conversation(owner, "group")
	path := "/v1/conversations/" + group + "/messages"
	ids := make([]string, 3)
	for i := range ids {
		var message struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(f.request("POST", path, owner.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"live pagination"}`)}, 201), &message) != nil {
			t.Fatal("message response")
		}
		ids[i] = message.ID
	}
	f.request("DELETE", path, owner.Access, map[string]any{"ids": ids[1:]}, 204)
	var live []struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(f.request("GET", path+"?limit=1&live_only=true", owner.Access, nil, 200), &live) != nil || len(live) != 1 || live[0].ID != ids[0] {
		t.Fatal("deleted rows consumed the live page limit")
	}
	if json.Unmarshal(f.request("GET", path+"?limit=1&live_only=true&before="+ids[2], owner.Access, nil, 200), &live) != nil || len(live) != 1 || live[0].ID != ids[0] {
		t.Fatal("deleted cursor did not preserve ordinal pagination")
	}
	var tombstones []struct {
		ID      string `json:"id"`
		Deleted any    `json:"deleted_at"`
	}
	if json.Unmarshal(f.request("GET", path+"?limit=1", owner.Access, nil, 200), &tombstones) != nil || len(tombstones) != 1 || tombstones[0].ID != ids[2] || tombstones[0].Deleted == nil {
		t.Fatal("default history lost tombstone state")
	}
	f.request("GET", "/v1/messages/"+ids[2], owner.Access, nil, 200)
	f.request("GET", path+"?live_only=true", outsider.Access, nil, 404)
	f.request("GET", path+"?live_only=invalid", owner.Access, nil, 400)
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET expires_at=now()-interval '1 second' WHERE id=$1`, ids[0]); err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal(f.request("GET", path+"?live_only=true", owner.Access, nil, 200), &live) != nil || len(live) != 0 {
		t.Fatal("expired message entered live history")
	}
}
