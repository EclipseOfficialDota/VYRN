package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"slices"
	"sort"
	"strings"
	"testing"

	"github.com/google/uuid"
)

func TestIntegrationReactionSelection(t *testing.T) {
	f := setup(t)
	a, b, outsider := f.register("selection-owner@example.com"), f.register("selection-member@example.com"), f.register("selection-outsider@example.com")
	group := f.conversation(a, "group", b.User)
	var sent map[string]string
	if err := json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"reaction selection"}`)}, 201), &sent); err != nil {
		t.Fatal(err)
	}
	id := sent["id"]
	path := "/v1/messages/" + id + "/reactions/selection"
	set := func(actor account, emojis []string, status int) {
		t.Helper()
		f.request("PUT", path, actor.Access, map[string]any{"emojis": emojis}, status)
	}
	selection := func(actor account, want ...string) {
		t.Helper()
		var actual []string
		if err := f.db.QueryRow(context.Background(), `SELECT COALESCE(array_agg(emoji ORDER BY emoji),'{}'::text[]) FROM reactions WHERE message_id=$1 AND user_id=$2`, id, actor.User).Scan(&actual); err != nil {
			t.Fatal(err)
		}
		sort.Strings(want)
		sort.Strings(actual)
		if !slices.Equal(actual, want) {
			t.Fatalf("selection mismatch: got %q want %q", actual, want)
		}
	}
	events := func() int {
		t.Helper()
		var count int
		if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM events WHERE kind='message.reaction'`).Scan(&count); err != nil {
			t.Fatal(err)
		}
		return count
	}

	f.request("PUT", "/v1/messages/"+id+"/reactions", a.Access, map[string]string{"emoji": "👍"}, 204)
	set(b, []string{"👍", "❤️", "🔥"}, 204)
	set(b, []string{"👍", "❤️", "🔥"}, 204) // Lost-ack replay must not duplicate counts.
	selection(a, "👍")
	selection(b, "👍", "❤️", "🔥")
	var visible struct {
		Reactions []struct {
			Emoji string `json:"emoji"`
			Count int    `json:"count"`
			Mine  bool   `json:"mine"`
		} `json:"reactions"`
	}
	if err := json.Unmarshal(f.request("GET", "/v1/messages/"+id, b.Access, nil, 200), &visible); err != nil || len(visible.Reactions) != 3 {
		t.Fatal("selection read model missing", err)
	}
	for _, reaction := range visible.Reactions {
		want := 1
		if reaction.Emoji == "👍" {
			want = 2
		}
		if !reaction.Mine || reaction.Count != want {
			t.Fatal("selection mine/count does not match committed rows")
		}
	}
	set(b, []string{"🎉"}, 204)
	selection(b, "🎉")
	selection(a, "👍")
	set(b, []string{}, 204)
	selection(b)
	selection(a, "👍")
	set(b, []string{"👍"}, 204)
	before := events()
	for _, input := range []any{
		map[string]any{}, map[string]any{"emojis": nil},
		map[string]any{"emojis": []string{""}}, map[string]any{"emojis": []string{"👍", "👍"}},
		map[string]any{"emojis": []string{"👍", "❤️", "🔥", "🎉"}},
		map[string]any{"emojis": []string{strings.Repeat("🔎", 17)}},
	} {
		f.request("PUT", path, b.Access, input, 400)
		selection(b, "👍")
	}
	f.request("PUT", path, "", map[string]any{"emojis": []string{}}, 401)
	set(outsider, []string{"🔥"}, 404)
	if events() != before {
		t.Fatal("rejected selection emitted an event")
	}

	// A real failure after DELETE and the first INSERT must restore the previous set and event log.
	if _, err := f.db.Exec(context.Background(), `CREATE FUNCTION reject_selected_reaction() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture selection write failure'; END $$`); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec(context.Background(), `CREATE TRIGGER reject_selected_reaction BEFORE INSERT ON reactions FOR EACH ROW WHEN (NEW.emoji='🔥') EXECUTE FUNCTION reject_selected_reaction()`); err != nil {
		t.Fatal(err)
	}
	set(b, []string{"❤️", "🔥"}, 500)
	selection(b, "👍")
	selection(a, "👍")
	if events() != before {
		t.Fatal("rolled-back selection emitted an event")
	}
	if _, err := f.db.Exec(context.Background(), `DROP TRIGGER reject_selected_reaction ON reactions`); err != nil {
		t.Fatal(err)
	}

	// Concurrent complete replacements must finish as one complete set, never their union/subset.
	start, statuses := make(chan struct{}), make(chan int, 2)
	for _, emojis := range [][]string{{"🔥", "❤️"}, {"🎉", "🚀"}} {
		body, _ := json.Marshal(map[string]any{"emojis": emojis})
		go func() {
			<-start
			req, err := http.NewRequest("PUT", f.http.URL+path, bytes.NewReader(body))
			if err != nil {
				statuses <- 0
				return
			}
			req.Header.Set("Authorization", "Bearer "+b.Access)
			req.Header.Set("Content-Type", "application/json")
			response, err := f.http.Client().Do(req)
			if err != nil {
				statuses <- 0
				return
			}
			response.Body.Close()
			statuses <- response.StatusCode
		}()
	}
	close(start)
	if <-statuses != 204 || <-statuses != 204 {
		t.Fatal("concurrent selection request failed")
	}
	var final []string
	if err := f.db.QueryRow(context.Background(), `SELECT array_agg(emoji ORDER BY emoji) FROM reactions WHERE message_id=$1 AND user_id=$2`, id, b.User).Scan(&final); err != nil {
		t.Fatal(err)
	}
	x, y := []string{"🔥", "❤️"}, []string{"🎉", "🚀"}
	sort.Strings(final)
	sort.Strings(x)
	sort.Strings(y)
	if !slices.Equal(final, x) && !slices.Equal(final, y) {
		t.Fatal("concurrent selection became a partial/mixed set")
	}
	selection(a, "👍")

	f.request("DELETE", "/v1/conversations/"+group+"/members/"+b.User, a.Access, nil, 204)
	set(b, []string{}, 404)
	selection(a, "👍")
	before = events()
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET expires_at=now()-interval '1 second' WHERE id=$1`, id); err != nil {
		t.Fatal(err)
	}
	set(a, []string{"🔥"}, 404)
	selection(a, "👍")
	if events() != before {
		t.Fatal("expired-message selection emitted an event")
	}
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET expires_at=NULL WHERE id=$1`, id); err != nil {
		t.Fatal(err)
	}
	f.request("DELETE", "/v1/messages/"+id, a.Access, nil, 204)
	set(a, []string{"🔥"}, 404)
	if events() != before {
		t.Fatal("deleted-message selection emitted an event")
	}
}
