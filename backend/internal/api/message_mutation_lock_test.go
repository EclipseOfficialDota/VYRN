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

func TestMessageMutationRechecksAfterLock(t *testing.T) {
	for _, change := range []string{"removed", "demoted", "expired"} {
		t.Run(change, func(t *testing.T) {
			f := setup(t)
			a, b := f.register("mutation-owner@example.com"), f.register("mutation-member@example.com")
			group := f.conversation(a, "group", b.User)
			var sent struct {
				ID string `json:"id"`
			}
			if err := json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"locked mutation"}`)}, 201), &sent); err != nil {
				t.Fatal(err)
			}
			path, body, want := "/v1/messages/"+sent.ID+"/reactions/selection", `{"emojis":["👍"]}`, 404
			if change == "demoted" {
				f.request("POST", "/v1/conversations/"+group+"/members", a.Access, map[string]string{"user_id": b.User, "role": "admin"}, 204)
				path, body, want = "/v1/messages/"+sent.ID+"/pin", `{"pinned":true}`, 403
			}
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			if change == "expired" {
				if _, err := f.db.Exec(ctx, `UPDATE messages SET expires_at=clock_timestamp()+interval '1 second' WHERE id=$1`, sent.ID); err != nil {
					t.Fatal(err)
				}
			}
			tx, err := f.db.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			var blocker int
			var locked string
			if err = tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&blocker); err != nil {
				t.Fatal(err)
			}
			if err = tx.QueryRow(ctx, `SELECT id::text FROM messages WHERE id=$1 FOR UPDATE`, sent.ID).Scan(&locked); err != nil {
				t.Fatal(err)
			}
			if change == "removed" {
				_, err = tx.Exec(ctx, `DELETE FROM members WHERE conversation_id=$1 AND user_id=$2`, group, b.User)
			} else if change == "demoted" {
				_, err = tx.Exec(ctx, `UPDATE members SET role='member' WHERE conversation_id=$1 AND user_id=$2`, group, b.User)
			}
			if err != nil {
				t.Fatal(err)
			}
			outcome := make(chan int, 1)
			go func() {
				request, _ := http.NewRequestWithContext(ctx, "PUT", f.http.URL+path, bytes.NewBufferString(body))
				request.Header.Set("Authorization", "Bearer "+b.Access)
				request.Header.Set("Content-Type", "application/json")
				response, err := f.http.Client().Do(request)
				if err != nil {
					outcome <- 0
					return
				}
				response.Body.Close()
				outcome <- response.StatusCode
			}()
			waitSignalBlock(t, f, ctx, blocker)
			if change == "expired" {
				for {
					var expired bool
					if err = f.db.QueryRow(ctx, `SELECT expires_at<=clock_timestamp() FROM messages WHERE id=$1`, sent.ID).Scan(&expired); err != nil {
						t.Fatal(err)
					}
					if expired {
						break
					}
					time.Sleep(10 * time.Millisecond)
				}
			}
			if err = tx.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case status := <-outcome:
				if status != want {
					t.Fatalf("waiting %s mutation returned %d, want %d", change, status, want)
				}
			case <-ctx.Done():
				t.Fatal("waiting mutation did not finish")
			}
			var reactions, events int
			var pinned bool
			if err = f.db.QueryRow(ctx, `SELECT (SELECT count(*) FROM reactions WHERE message_id=$1),pinned,(SELECT count(*) FROM events WHERE kind IN ('message.reaction','message.pinned')) FROM messages WHERE id=$1`, sent.ID).Scan(&reactions, &pinned, &events); err != nil {
				t.Fatal(err)
			}
			if reactions != 0 || pinned || events != 0 {
				t.Fatal("rejected waiting mutation persisted state or events")
			}
		})
	}
}

func TestMembershipRoleRechecksAfterConversationLock(t *testing.T) {
	for _, removed := range []bool{false, true} {
		t.Run(map[bool]string{false: "demoted", true: "removed"}[removed], func(t *testing.T) {
			f := setup(t)
			a, b, c := f.register("role-owner@example.com"), f.register("role-admin@example.com"), f.register("role-member@example.com")
			group := f.conversation(a, "group", b.User, c.User)
			f.request("POST", "/v1/conversations/"+group+"/members", a.Access, map[string]string{"user_id": b.User, "role": "admin"}, 204)
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			tx, err := f.db.Begin(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback(context.Background())
			var blocker int
			var locked string
			if err = tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&blocker); err != nil {
				t.Fatal(err)
			}
			if err = tx.QueryRow(ctx, `SELECT id::text FROM conversations WHERE id=$1 FOR UPDATE`, group).Scan(&locked); err != nil {
				t.Fatal(err)
			}
			if removed {
				_, err = tx.Exec(ctx, `DELETE FROM members WHERE conversation_id=$1 AND user_id=$2`, group, b.User)
			} else {
				_, err = tx.Exec(ctx, `UPDATE members SET role='member' WHERE conversation_id=$1 AND user_id=$2`, group, b.User)
			}
			if err != nil {
				t.Fatal(err)
			}
			outcome := make(chan int, 1)
			go func() {
				request, _ := http.NewRequestWithContext(ctx, "DELETE", f.http.URL+"/v1/conversations/"+group+"/members/"+c.User, nil)
				request.Header.Set("Authorization", "Bearer "+b.Access)
				response, err := f.http.Client().Do(request)
				if err != nil {
					outcome <- 0
					return
				}
				response.Body.Close()
				outcome <- response.StatusCode
			}()
			waitSignalBlock(t, f, ctx, blocker)
			if err = tx.Commit(ctx); err != nil {
				t.Fatal(err)
			}
			select {
			case status := <-outcome:
				if status != 403 {
					t.Fatalf("waiting membership mutation returned %d, want 403", status)
				}
			case <-ctx.Done():
				t.Fatal("waiting membership mutation did not finish")
			}
			var members, events int
			if err = f.db.QueryRow(ctx, `SELECT (SELECT count(*) FROM members WHERE conversation_id=$1 AND user_id=$2),(SELECT count(*) FROM events WHERE kind='member.removed')`, group, c.User).Scan(&members, &events); err != nil {
				t.Fatal(err)
			}
			if members != 1 || events != 0 {
				t.Fatal("stale administrator removed a member or emitted an event")
			}
		})
	}
}

func TestMessageMutationLocksMembershipUntilCommit(t *testing.T) {
	f := setup(t)
	a, b := f.register("linear-owner@example.com"), f.register("linear-member@example.com")
	group := f.conversation(a, "group", b.User)
	var sent struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"membership serialization"}`)}, 201), &sent); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	tx, err := f.db.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	var blocker int
	var locked string
	if err = tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&blocker); err != nil {
		t.Fatal(err)
	}
	if err = tx.QueryRow(ctx, `SELECT id::text FROM messages WHERE id=$1 FOR UPDATE`, sent.ID).Scan(&locked); err != nil {
		t.Fatal(err)
	}
	start := func(actor account, method, path, body string) <-chan int {
		outcome := make(chan int, 1)
		go func() {
			request, _ := http.NewRequestWithContext(ctx, method, f.http.URL+path, bytes.NewBufferString(body))
			request.Header.Set("Authorization", "Bearer "+actor.Access)
			request.Header.Set("Content-Type", "application/json")
			response, err := f.http.Client().Do(request)
			if err != nil {
				outcome <- 0
				return
			}
			response.Body.Close()
			outcome <- response.StatusCode
		}()
		return outcome
	}
	selection := start(b, "PUT", "/v1/messages/"+sent.ID+"/reactions/selection", `{"emojis":["👍"]}`)
	mutationPID := waitSignalBlock(t, f, ctx, blocker)
	removal := start(a, "DELETE", "/v1/conversations/"+group+"/members/"+b.User, "")
	waitSignalBlock(t, f, ctx, mutationPID) // The real removal API must wait for the mutation's conversation lock.
	if err = tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	for _, outcome := range []<-chan int{selection, removal} {
		select {
		case status := <-outcome:
			if status != 204 {
				t.Fatalf("serialized request returned %d", status)
			}
		case <-ctx.Done():
			t.Fatal("serialized requests did not finish")
		}
	}
	var members, reactions int
	if err = f.db.QueryRow(ctx, `SELECT (SELECT count(*) FROM members WHERE conversation_id=$1 AND user_id=$2),(SELECT count(*) FROM reactions WHERE message_id=$3 AND user_id=$2 AND emoji='👍')`, group, b.User, sent.ID).Scan(&members, &reactions); err != nil {
		t.Fatal(err)
	}
	if members != 0 || reactions != 1 {
		t.Fatal("authorized selection/removal did not serialize")
	}
	f.request("PUT", "/v1/messages/"+sent.ID+"/reactions/selection", b.Access, map[string]any{"emojis": []string{}}, 404)
}
