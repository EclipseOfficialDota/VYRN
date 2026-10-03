package api

import (
	"encoding/json"
	"testing"
)

func TestProfilePersonalMetadata(t *testing.T) {
	f := setup(t)
	a := f.register("metadata-a@example.com")
	b := f.register("metadata-b@example.com")
	c := f.register("metadata-c@example.com")
	group := f.conversation(a, "group", b.User)
	profile := func(viewer account, target string) map[string]any {
		t.Helper()
		var out map[string]any
		if json.Unmarshal(f.request("GET", "/v1/users/"+target, viewer.Access, nil, 200), &out) != nil {
			t.Fatal("profile response")
		}
		return out
	}
	if got := profile(a, b.User); got["blocked"] != false || got["common_group_count"] != float64(1) || got["email"] != nil {
		t.Fatal("shared profile metadata")
	}
	f.request("PUT", "/v1/blocks/"+b.User, a.Access, nil, 204)
	if profile(a, b.User)["blocked"] != true || profile(b, a.User)["blocked"] != false || profile(c, b.User)["common_group_count"] != float64(0) {
		t.Fatal("metadata must be scoped to viewer")
	}
	f.request("DELETE", "/v1/conversations/"+group+"/members/"+b.User, a.Access, nil, 204)
	if profile(a, b.User)["common_group_count"] != float64(0) {
		t.Fatal("removed membership must not count")
	}
	f.request("DELETE", "/v1/blocks/"+b.User, a.Access, nil, 204)
	if profile(a, b.User)["blocked"] != false {
		t.Fatal("unblocked metadata")
	}
}
