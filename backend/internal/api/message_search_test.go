package api

import (
	"context"
	"encoding/json"
	"net/url"
	"strings"
	"testing"

	"github.com/google/uuid"
)

func TestIntegrationSearchTotalAndPagination(t *testing.T) {
	f := setup(t)
	a, b, outsider := f.register("search-a@example.com"), f.register("search-b@example.com"), f.register("search-outsider@example.com")
	group := f.conversation(a, "group", b.User)
	path := "/v1/conversations/" + group + "/messages"
	send := func(conversation, text string) string {
		t.Helper()
		payload, _ := json.Marshal(map[string]string{"text": text})
		data := f.request("POST", "/v1/conversations/"+conversation+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: payload}, 201)
		var result map[string]string
		if err := json.Unmarshal(data, &result); err != nil {
			t.Fatal(err)
		}
		return result["id"]
	}
	first := send(group, "Needle first")
	send(group, "unrelated text")
	second := send(group, "needle second")
	deleted := send(group, "needle deleted")
	expired := send(group, "needle expired")
	third := send(group, "needle third")
	literal := send(group, "literal %_ marker")
	persian := send(group, "سلام دنیا")
	send(f.conversation(a, "group"), "needle other conversation")
	f.request("DELETE", "/v1/messages/"+deleted, a.Access, nil, 204)
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET expires_at=now()-interval '1 second' WHERE id=$1`, expired); err != nil {
		t.Fatal(err)
	}
	page := func(query, before string, total int, ids ...string) {
		t.Helper()
		var result struct {
			Total    int `json:"total"`
			Messages []struct {
				ID string `json:"id"`
			} `json:"messages"`
		}
		data := f.request("GET", path+"?include_total=true&limit=2&q="+url.QueryEscape(query)+"&before="+before, b.Access, nil, 200)
		if err := json.Unmarshal(data, &result); err != nil {
			t.Fatal(err)
		}
		if result.Total != total || len(result.Messages) != len(ids) || result.Messages == nil {
			t.Fatalf("search count/page: total=%d, page=%d, want total=%d, page=%d", result.Total, len(result.Messages), total, len(ids))
		}
		for i, id := range ids {
			if result.Messages[i].ID != id {
				t.Fatal("search ordering, cursor or conversation isolation changed")
			}
		}
	}
	page("NEEDLE", "", 3, third, second)
	page("needle", second, 3, first)
	page("needle", first, 3)
	page("no match", "", 0)
	page("%_", "", 1, literal)
	page("سلام", "", 1, persian)
	var legacy []json.RawMessage
	if err := json.Unmarshal(f.request("GET", path+"?q=needle&limit=2", b.Access, nil, 200), &legacy); err != nil || len(legacy) != 2 {
		t.Fatal("existing array search contract changed")
	}
	f.request("GET", path+"?q=needle&include_total=true", "", nil, 401)
	f.request("GET", path+"?q=needle&include_total=true", outsider.Access, nil, 404)
	private := f.conversation(a, "private", b.User)
	f.request("GET", "/v1/conversations/"+private+"/messages?q=needle&include_total=true", a.Access, nil, 422)
	f.request("GET", path+"?include_total=true", a.Access, nil, 400)
	f.request("GET", path+"?q=needle&include_total=TRUE", a.Access, nil, 400)
	f.request("GET", path+"?q="+url.QueryEscape(strings.Repeat("🔎", 101))+"&include_total=true", a.Access, nil, 400)
}
