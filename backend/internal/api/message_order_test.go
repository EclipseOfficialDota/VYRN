package api

import (
	"context"
	"encoding/json"
	"sort"
	"testing"

	"github.com/google/uuid"
	"xyz.eclipseofficial/vyrn/internal/database"
)

type orderedMessage struct {
	ID      string         `json:"id"`
	Ordinal int            `json:"ordinal"`
	Deleted *string        `json:"deleted_at"`
	Payload map[string]any `json:"payload"`
}

func TestIntegrationMessageOrder(t *testing.T) {
	f := setup(t)
	a, b, outsider := f.register("order-a@example.com"), f.register("order-b@example.com"), f.register("order-outsider@example.com")
	group := f.conversation(a, "group", b.User)
	path := "/v1/conversations/" + group + "/messages"
	ids := []string{}
	for i := 0; i < 3; i++ {
		var sent struct {
			ID string `json:"id"`
		}
		data := f.request("POST", path, a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"ordered"}`)}, 201)
		if json.Unmarshal(data, &sent) != nil {
			t.Fatal("send response")
		}
		ids = append(ids, sent.ID)
	}
	// The pagination key must not change if wall-clock timestamps are equal or skewed.
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET created_at='2020-01-01' WHERE id=$1`, ids[2]); err != nil {
		t.Fatal(err)
	}
	previous := int(^uint32(0) >> 1)
	query := "?limit=1"
	for i := 2; i >= 0; i-- {
		var page []orderedMessage
		if json.Unmarshal(f.request("GET", path+query, b.Access, nil, 200), &page) != nil || len(page) != 1 {
			t.Fatal("page")
		}
		m := page[0]
		if m.ID != ids[i] || m.Ordinal <= 0 || m.Ordinal >= previous {
			t.Fatal("unstable chronological IDs")
		}
		var single orderedMessage
		if json.Unmarshal(f.request("GET", "/v1/messages/"+m.ID, a.Access, nil, 200), &single) != nil || single.Ordinal != m.Ordinal {
			t.Fatal("message identity differs between devices")
		}
		previous = m.Ordinal
		query = "?limit=1&before=" + m.ID
	}
	var end []orderedMessage
	if json.Unmarshal(f.request("GET", path+query, a.Access, nil, 200), &end) != nil || len(end) != 0 {
		t.Fatal("pagination did not terminate")
	}
	f.request("GET", "/v1/messages/"+ids[0], outsider.Access, nil, 404)
	f.request("DELETE", "/v1/messages/"+ids[0], a.Access, nil, 204)
	var deleted orderedMessage
	if json.Unmarshal(f.request("GET", "/v1/messages/"+ids[0], b.Access, nil, 200), &deleted) != nil || deleted.Deleted == nil || len(deleted.Payload) != 0 {
		t.Fatal("tombstone leaked payload")
	}
	if _, err := f.db.Exec(context.Background(), `UPDATE messages SET expires_at=now()-interval '1 second' WHERE id=$1`, ids[1]); err != nil {
		t.Fatal(err)
	}
	f.request("GET", "/v1/messages/"+ids[1], b.Access, nil, 404)
	if err := f.s.maintenance(context.Background()); err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal(f.request("GET", "/v1/messages/"+ids[1], b.Access, nil, 200), &deleted) != nil || deleted.Deleted == nil || len(deleted.Payload) != 0 {
		t.Fatal("expired tombstone")
	}
	f.request("DELETE", "/v1/conversations/"+group+"/members/"+b.User, a.Access, nil, 204)
	f.request("GET", "/v1/messages/"+ids[2], b.Access, nil, 404)
}

func TestIntegrationMessageOrderUpgrade(t *testing.T) {
	f := setup(t)
	a := f.register("upgrade@example.com")
	group := f.conversation(a, "group")
	ids := []string{}
	for i := 0; i < 3; i++ {
		var sent struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"upgrade"}`)}, 201), &sent) != nil {
			t.Fatal("send")
		}
		ids = append(ids, sent.ID)
	}
	// Reconstruct version002 with actual messages, then apply version003 twice.
	ctx := context.Background()
	if _, err := f.db.Exec(ctx, `ALTER TABLE messages DROP COLUMN ordinal; DELETE FROM schema_migrations WHERE name='migrations/003_message_order.sql'; UPDATE messages SET created_at='2020-01-01'`); err != nil {
		t.Fatal(err)
	}
	if err := database.Migrate(ctx, f.db); err != nil {
		t.Fatal(err)
	}
	if err := database.Migrate(ctx, f.db); err != nil {
		t.Fatal(err)
	}
	sort.Strings(ids)
	for i, id := range ids {
		var ordinal int
		if err := f.db.QueryRow(ctx, `SELECT ordinal FROM messages WHERE id=$1`, id).Scan(&ordinal); err != nil || ordinal != i+1 {
			t.Fatal("ordered backfill")
		}
	}
	var sent struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"after upgrade"}`)}, 201), &sent) != nil {
		t.Fatal("send")
	}
	var ordinal int
	if err := f.db.QueryRow(ctx, `SELECT ordinal FROM messages WHERE id=$1`, sent.ID).Scan(&ordinal); err != nil || ordinal != 4 {
		t.Fatal("sequence did not continue after backfill")
	}
}
