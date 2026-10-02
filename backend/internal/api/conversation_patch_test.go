package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"testing"
)

func TestConversationPartialPatchPreservesOtherFields(t *testing.T) {
	f := setup(t)
	a, b := f.register("patch-owner@test.invalid"), f.register("patch-member@test.invalid")
	group := f.conversation(a, "group", b.User)
	path := "/v1/conversations/" + group
	f.request("PATCH", path, a.Access, map[string]any{"disappearing_seconds": 86400}, 204)
	f.request("PATCH", path, a.Access, map[string]any{"title": "Renamed"}, 204)
	assertState := func(wantTitle string, wantSeconds int) {
		t.Helper()
		var title string
		var seconds int
		if err := f.db.QueryRow(context.Background(), "SELECT title,disappearing_seconds FROM conversations WHERE id=$1", group).Scan(&title, &seconds); err != nil || title != wantTitle || seconds != wantSeconds {
			t.Fatal("partial patch overwrote an omitted field")
		}
	}
	assertState("Renamed", 86400)
	for _, input := range []map[string]any{{}, {"title": ""}, {"disappearing_seconds": -1}, {"disappearing_seconds": 31536001}} {
		f.request("PATCH", path, a.Access, input, 400)
	}
	f.request("PATCH", path, b.Access, map[string]any{"title": "Forbidden"}, 403)
	assertState("Renamed", 86400)
	for attempt := 0; attempt < 5; attempt++ {
		outcomes := make(chan bool, 2)
		for _, input := range []map[string]any{{"title": "Concurrent rename"}, {"disappearing_seconds": 900 + attempt}} {
			go func(input map[string]any) {
				body, _ := json.Marshal(input)
				req, _ := http.NewRequest("PATCH", f.http.URL+path, bytes.NewReader(body))
				req.Header.Set("Authorization", "Bearer "+a.Access)
				req.Header.Set("Content-Type", "application/json")
				response, err := f.http.Client().Do(req)
				if err != nil {
					outcomes <- false
					return
				}
				response.Body.Close()
				outcomes <- response.StatusCode == 204
			}(input)
		}
		first, second := <-outcomes, <-outcomes
		if !first || !second {
			t.Fatal("concurrent patch request failed")
		}
		assertState("Concurrent rename", 900+attempt)
	}
	f.request("PATCH", path, a.Access, map[string]any{"disappearing_seconds": 0}, 204)
	assertState("Concurrent rename", 0)
}
