package api

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"
	"xyz.eclipseofficial/vyrn/internal/database"
)

func TestBlockedUsersPrivacyPaginationAndUpgrade(t *testing.T) {
	f := setup(t)
	a, b, c := f.register("blocks-a@test.invalid"), f.register("blocks-b@test.invalid"), f.register("blocks-c@test.invalid")
	ctx := context.Background()
	if _, err := f.db.Exec(ctx, `ALTER TABLE blocks DROP COLUMN created_at CASCADE; DELETE FROM schema_migrations WHERE name='migrations/006_block_dates.sql'`); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec(ctx, `INSERT INTO blocks(user_id,blocked_id) VALUES($1,$2)`, a.User, b.User); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := database.Migrate(ctx, f.db); err != nil {
			t.Fatal(err)
		}
	}
	var legacy *time.Time
	if err := f.db.QueryRow(ctx, `SELECT created_at FROM blocks WHERE user_id=$1 AND blocked_id=$2`, a.User, b.User).Scan(&legacy); err != nil || legacy != nil {
		t.Fatal("legacy block date must remain unknown")
	}
	f.request("GET", "/v1/blocks", "", nil, 401)
	f.request("GET", "/v1/blocks/"+b.User, "", nil, 401)
	f.request("PUT", "/v1/blocks/"+c.User, a.Access, nil, 204)
	var created time.Time
	if err := f.db.QueryRow(ctx, `SELECT created_at FROM blocks WHERE user_id=$1 AND blocked_id=$2`, a.User, c.User).Scan(&created); err != nil || created.IsZero() {
		t.Fatal("new block date missing")
	}
	f.request("PUT", "/v1/blocks/"+c.User, a.Access, nil, 204)
	var repeated time.Time
	if err := f.db.QueryRow(ctx, `SELECT created_at FROM blocks WHERE user_id=$1 AND blocked_id=$2`, a.User, c.User).Scan(&repeated); err != nil || !repeated.Equal(created) {
		t.Fatal("repeated block changed its creation date")
	}
	list := func(viewer account, query string) (int, []map[string]any) {
		t.Helper()
		var page struct {
			Count int              `json:"count"`
			Items []map[string]any `json:"items"`
		}
		if err := json.Unmarshal(f.request("GET", "/v1/blocks"+query, viewer.Access, nil, 200), &page); err != nil || page.Items == nil {
			t.Fatal("blocked page")
		}
		for _, row := range page.Items {
			if len(row) != 6 || row["email"] != nil || row["admin"] != nil {
				t.Fatal("non-public block-list fields")
			}
		}
		return page.Count, page.Items
	}
	if count, rows := list(a, "?limit=1"); count != 2 || len(rows) != 1 || rows[0]["id"] != c.User || rows[0]["created_at"] == nil {
		t.Fatal("first page must contain actual new block")
	}
	if count, rows := list(a, "?offset=1&limit=1"); count != 2 || len(rows) != 1 || rows[0]["id"] != b.User || rows[0]["created_at"] != nil {
		t.Fatal("legacy page/date")
	}
	if count, rows := list(a, "?offset=2"); count != 2 || len(rows) != 0 {
		t.Fatal("empty page must retain total count")
	}
	if count, rows := list(b, ""); count != 0 || len(rows) != 0 {
		t.Fatal("block list leaked another owner")
	}
	for _, query := range []string{"?limit=0", "?limit=101", "?offset=-1", "?offset=bad", "?offset=2147483648"} {
		f.request("GET", "/v1/blocks"+query, a.Access, nil, 400)
	}
	f.request("PUT", "/v1/blocks/"+a.User, a.Access, nil, 400)
	f.request("DELETE", "/v1/blocks/"+a.User, a.Access, nil, 400)
	f.request("PUT", "/v1/blocks/"+uuid.NewString(), a.Access, nil, 404)
	f.request("GET", "/v1/blocks/bad", a.Access, nil, 400)
	f.request("DELETE", "/v1/blocks/"+c.User, b.Access, nil, 204)
	var state map[string]bool
	if json.Unmarshal(f.request("GET", "/v1/blocks/"+c.User, a.Access, nil, 200), &state) != nil || !state["blocked"] {
		t.Fatal("foreign unblock changed owned state")
	}
	f.request("DELETE", "/v1/blocks/"+c.User, a.Access, nil, 204)
	f.request("DELETE", "/v1/blocks/"+c.User, a.Access, nil, 204)
	if json.Unmarshal(f.request("GET", "/v1/blocks/"+c.User, a.Access, nil, 200), &state) != nil || state["blocked"] {
		t.Fatal("unblock state")
	}
	var events struct {
		Events []struct {
			Kind string         `json:"kind"`
			Body map[string]any `json:"body"`
		} `json:"events"`
	}
	if json.Unmarshal(f.request("GET", "/v1/sync?cursor=0", a.Access, nil, 200), &events) != nil || len(events.Events) != 2 || events.Events[0].Kind != "block.updated" || events.Events[0].Body["blocked"] != true || events.Events[1].Body["blocked"] != false {
		t.Fatal("owned block events must match committed changes, without duplicates")
	}
	if json.Unmarshal(f.request("GET", "/v1/sync?cursor=0", b.Access, nil, 200), &events) != nil || len(events.Events) != 0 {
		t.Fatal("block events disclosed to target")
	}
	if _, err := f.db.Exec(ctx, `UPDATE users SET disabled=true WHERE id=$1`, c.User); err != nil {
		t.Fatal(err)
	}
	f.request("PUT", "/v1/blocks/"+c.User, a.Access, nil, 404)
	if count, rows := list(a, ""); count != 1 || len(rows) != 1 {
		t.Fatal("failed block mutated list")
	}
}
