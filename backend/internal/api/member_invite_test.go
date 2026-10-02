package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"testing"
)

func TestMemberInvitationPreservesExistingRole(t *testing.T) {
	f := setup(t)
	a, b := f.register("invite-owner@test.invalid"), f.register("invite-member@test.invalid")
	group := f.conversation(a, "group", b.User)
	path := "/v1/conversations/" + group + "/members"
	invite := map[string]any{"user_id": b.User, "preserve_existing": true}
	f.request("POST", path, b.Access, invite, 403)
	f.request("POST", path, a.Access, map[string]any{"user_id": b.User, "role": "admin"}, 204)
	f.request("POST", path, b.Access, invite, 204)
	f.request("POST", path, a.Access, map[string]any{"user_id": a.User, "preserve_existing": true}, 204)
	for attempt := 0; attempt < 5; attempt++ {
		f.request("POST", path, a.Access, map[string]any{"user_id": b.User, "role": "member"}, 204)
		outcomes := make(chan bool, 2)
		for _, input := range []map[string]any{invite, {"user_id": b.User, "role": "admin"}} {
			go func(input map[string]any) {
				body, _ := json.Marshal(input)
				req, _ := http.NewRequest("POST", f.http.URL+path, bytes.NewReader(body))
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
			t.Fatal("concurrent invitation/promotion failed")
		}
		var owner, member string
		if err := f.db.QueryRow(context.Background(), `SELECT (SELECT role FROM members WHERE conversation_id=$1 AND user_id=$2),(SELECT role FROM members WHERE conversation_id=$1 AND user_id=$3)`, group, a.User, b.User).Scan(&owner, &member); err != nil || owner != "owner" || member != "admin" {
			t.Fatal("invitation changed an existing role")
		}
	}
	c := f.register("invite-new@test.invalid")
	f.request("POST", path, b.Access, map[string]any{"user_id": c.User, "preserve_existing": true}, 204)
	var role string
	if err := f.db.QueryRow(context.Background(), "SELECT role FROM members WHERE conversation_id=$1 AND user_id=$2", group, c.User).Scan(&role); err != nil || role != "member" {
		t.Fatal("new member invitation failed")
	}
}
