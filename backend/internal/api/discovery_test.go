package api

import (
	"context"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
)

func TestUserDiscoveryPrivacyAndBounds(t *testing.T) {
	f := setup(t)
	a, b, c, d := f.register("discovery-owner@test.invalid"), f.register("private-login@test.invalid"), f.register("hidden@test.invalid"), f.register("disabled@test.invalid")
	for id, name := range map[string]string{a.User: "Alpha self", b.User: "Alpha% دوست", c.User: "Alpha other", d.User: "Alpha disabled"} {
		if _, err := f.db.Exec(context.Background(), "UPDATE users SET display_name=$2 WHERE id=$1", id, name); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := f.db.Exec(context.Background(), "UPDATE users SET disabled=true WHERE id=$1", d.User); err != nil {
		t.Fatal(err)
	}
	f.request("GET", "/v1/users/search?q=Alpha", "", nil, 401)
	search := func(q string) []map[string]any {
		t.Helper()
		data := f.request("GET", "/v1/users/search?q="+url.QueryEscape(q), a.Access, nil, 200)
		var users []map[string]any
		if err := json.Unmarshal(data, &users); err != nil {
			t.Fatal(err)
		}
		for _, user := range users {
			if len(user) != 5 || user["email"] != nil || user["admin"] != nil || user["first_name"] == nil || user["last_name"] == nil {
				t.Fatal("discovery exposed non-public fields")
			}
		}
		return users
	}
	if users := search("aLpHa"); len(users) != 2 {
		t.Fatal("case-insensitive search must exclude self and disabled users")
	}
	for _, q := range []string{"Alpha%", "دوست"} {
		if users := search(q); len(users) != 1 || users[0]["id"] != b.User {
			t.Fatal("literal wildcard or Unicode discovery failed")
		}
	}
	for _, q := range []string{"", "a", "private-login"} {
		if len(search(q)) != 0 {
			t.Fatal("empty, short or email queries must not reveal users")
		}
	}
	data := f.request("GET", "/v1/users/search?q=Alpha&limit=1", a.Access, nil, 200)
	var limited []map[string]any
	if json.Unmarshal(data, &limited) != nil || len(limited) != 1 {
		t.Fatal("discovery limit failed")
	}
	for _, suffix := range []string{"limit=0", "limit=51", "limit=invalid", "q=" + strings.Repeat("a", 101)} {
		f.request("GET", "/v1/users/search?"+suffix, a.Access, nil, 400)
	}
	f.request("PUT", "/v1/blocks/"+b.User, a.Access, nil, 204)
	f.request("PUT", "/v1/blocks/"+a.User, c.Access, nil, 204)
	if len(search("Alpha")) != 0 {
		t.Fatal("blocked users must be excluded in both directions")
	}
}
