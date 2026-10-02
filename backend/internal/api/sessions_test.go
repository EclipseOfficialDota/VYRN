package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"github.com/google/uuid"
)

func TestRevokeOtherSessions(t *testing.T) {
	f := setup(t)
	owner := f.register("sessions-owner@example.com")
	outsider := f.register("sessions-outsider@example.com")
	others := make([]tokenPair, 2)
	for i := range others {
		body := f.request("POST", "/v1/auth/login", "", map[string]string{"email": "sessions-owner@example.com", "password": "strong test password", "device_id": uuid.NewString(), "device_name": "Other device"}, 200)
		if err := json.Unmarshal(body, &others[i]); err != nil {
			t.Fatal("login response")
		}
	}
	f.request("DELETE", "/v1/sessions", "", nil, 401)
	for _, other := range others {
		f.request("GET", "/v1/me", other.Access, nil, 200)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	sockets := make([]*websocket.Conn, len(others))
	for i, other := range others {
		conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(f.http.URL, "http")+"/v1/ws", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer " + other.Access}}})
		if err != nil {
			t.Fatal("session websocket connection")
		}
		defer conn.CloseNow()
		sockets[i] = conn
		var ready struct {
			Type string `json:"type"`
		}
		if err := wsjson.Read(ctx, conn, &ready); err != nil || ready.Type != "ready" {
			t.Fatal("session websocket readiness")
		}
	}
	f.request("DELETE", "/v1/sessions", owner.Access, nil, 204)
	for _, conn := range sockets {
		var incoming any
		if err := wsjson.Read(ctx, conn, &incoming); websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
			t.Fatal("revoked websocket must close with policy violation")
		}
	}
	for _, other := range others {
		f.request("GET", "/v1/me", other.Access, nil, 401)
		f.request("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": other.Refresh}, 401)
	}
	f.request("GET", "/v1/me", owner.Access, nil, 200)
	f.request("GET", "/v1/me", outsider.Access, nil, 200)
	var sessions []struct {
		Current bool `json:"current"`
	}
	if err := json.Unmarshal(f.request("GET", "/v1/sessions", owner.Access, nil, 200), &sessions); err != nil || len(sessions) != 1 || !sessions[0].Current {
		t.Fatal("current session must survive")
	}
	f.request("DELETE", "/v1/sessions", owner.Access, nil, 204)
	var renewed tokenPair
	if err := json.Unmarshal(f.request("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": owner.Refresh}, 200), &renewed); err != nil {
		t.Fatal("current refresh response")
	}
	f.request("GET", "/v1/me", renewed.Access, nil, 200)
	// Revocation frees device slots without banning subsequent authenticated logins.
	f.request("POST", "/v1/auth/login", "", map[string]string{"email": "sessions-owner@example.com", "password": "strong test password", "device_id": uuid.NewString(), "device_name": "Replacement device"}, 200)
}
