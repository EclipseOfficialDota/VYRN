package api

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"

	"github.com/google/uuid"
	"xyz.eclipseofficial/vyrn/internal/database"
)

func TestProfilePartialUpdates(t *testing.T) {
	f := setup(t)
	a := f.register("profile@example.com")
	b := f.register("other-profile@example.com")
	// Reconstruct the populated pre-profile schema and apply the new migration twice.
	if _, err := f.db.Exec(context.Background(), `ALTER TABLE users DROP COLUMN first_name, DROP COLUMN last_name, DROP COLUMN about; DELETE FROM schema_migrations WHERE name='migrations/005_profile_names.sql'`); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := database.Migrate(context.Background(), f.db); err != nil {
			t.Fatal(err)
		}
	}
	decode := func(data []byte) map[string]any {
		t.Helper()
		var out map[string]any
		if json.Unmarshal(data, &out) != nil {
			t.Fatal("profile response")
		}
		return out
	}
	before := decode(f.request("GET", "/v1/me", a.Access, nil, 200))
	if before["first_name"] != "Test user" || before["last_name"] != "" {
		t.Fatal("legacy display-name fallback")
	}
	avatar := uuid.NewString()
	if _, err := f.db.Exec(context.Background(), `INSERT INTO media(id,owner_id,kind,filename,content_type,size,sha256,storage_key,encrypted) VALUES($1,$2,'avatar','profile','image/png',1,'fixture',$3,false)`, avatar, a.User, avatar); err != nil {
		t.Fatal(err)
	}
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": avatar}, 200)
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"first_name": "نام", "last_name": "خانوادگی", "about": "دربارهٔ من"}, 200)
	after := decode(f.request("GET", "/v1/me", a.Access, nil, 200))
	if after["avatar_id"] != avatar || after["display_name"] != "نام خانوادگی" || after["first_name"] != "نام" || after["last_name"] != "خانوادگی" || after["about"] != "دربارهٔ من" {
		t.Fatal("structured name and unrelated fields must persist")
	}
	for _, bad := range []map[string]any{{"first_name": " "}, {"first_name": strings.Repeat("ن", 101)}, {"about": strings.Repeat("ن", 281)}, {"avatar_id": uuid.NewString(), "first_name": "wrong"}, {"display_name": "mixed", "last_name": "invalid"}, {}} {
		f.request("PATCH", "/v1/me", a.Access, bad, 400)
	}
	after = decode(f.request("GET", "/v1/me", a.Access, nil, 200))
	if after["first_name"] != "نام" || after["avatar_id"] != avatar || after["about"] != "دربارهٔ من" {
		t.Fatal("invalid changes must be atomic")
	}
	f.request("PATCH", "/v1/me", b.Access, map[string]any{"avatar_id": avatar}, 400)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		f.request("PATCH", "/v1/me", a.Access, map[string]any{"first_name": "جدید"}, 200)
	}()
	go func() {
		defer wg.Done()
		f.request("PATCH", "/v1/me", a.Access, map[string]any{"about": "زندگی"}, 200)
	}()
	wg.Wait()
	after = decode(f.request("GET", "/v1/me", a.Access, nil, 200))
	if after["first_name"] != "جدید" || after["last_name"] != "خانوادگی" || after["about"] != "زندگی" || after["avatar_id"] != avatar {
		t.Fatal("concurrent partial changes must survive")
	}
	public := decode(f.request("GET", "/v1/users/"+a.User, b.Access, nil, 200))
	if public["first_name"] != "جدید" || public["last_name"] != "خانوادگی" || public["email"] != nil {
		t.Fatal("public profile names without login identifier")
	}
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": nil}, 200)
	after = decode(f.request("PATCH", "/v1/me", a.Access, map[string]any{"display_name": "Legacy client"}, 200))
	if after["avatar_id"] != nil || after["first_name"] != "Legacy client" || after["last_name"] != "" || after["about"] != "زندگی" {
		t.Fatal("explicit avatar removal and legacy name edit")
	}
}
