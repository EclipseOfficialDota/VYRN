package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCommittedResponseHeldOnce(t *testing.T) {
	for _, scenario := range []struct {
		operation, method, path, body, message, expected string
		client                                           string
		status                                           int
	}{
		{"send", http.MethodPost, "/v1/conversations/group/messages", `{"id":"committed-message"}`, "", "committed-message", "", http.StatusCreated},
		{"send", http.MethodPost, "/v1/conversations/00000000-0000-0000-0000-000000000001/messages", `{"id":"staged-message"}`, "", "staged-message", "original-client", http.StatusCreated},
		{"edit", http.MethodPatch, "/v1/messages/message", "", "message", "message", "", http.StatusNoContent},
		{"delete", http.MethodDelete, "/v1/conversations/group/messages", "", "message", "message", "", http.StatusNoContent},
	} {
		t.Run(scenario.operation, func(t *testing.T) {
			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(scenario.status)
				w.Write([]byte(scenario.body))
			}))
			defer upstream.Close()
			target, _ := url.Parse(upstream.URL)
			state := filepath.Join(t.TempDir(), "held.json")
			proxy := faultProxy(target, http.DefaultTransport, "group", scenario.operation, scenario.message, scenario.client, state)
			other := httptest.NewRecorder()
			proxy.ServeHTTP(other, httptest.NewRequest(http.MethodPost, "/v1/conversations/other/messages", nil))
			if other.Code != scenario.status {
				t.Fatal("unrelated send changed")
			}
			if _, err := os.Stat(state); !os.IsNotExist(err) {
				t.Fatal("unrelated send armed the fault")
			}
			if scenario.client != "" {
				other := httptest.NewRecorder()
				proxy.ServeHTTP(other, httptest.NewRequest(http.MethodPost, scenario.path, strings.NewReader(`{"client_id":"another-client"}`)))
				if other.Code != scenario.status {
					t.Fatal("unrelated client response changed")
				}
				if _, err := os.Stat(state); !os.IsNotExist(err) {
					t.Fatal("unrelated client armed the fault")
				}
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			request := httptest.NewRequest(scenario.method, scenario.path, strings.NewReader(`{"client_id":"`+scenario.client+`"}`)).WithContext(ctx)
			recorder := httptest.NewRecorder()
			done := make(chan struct{})
			go func() { proxy.ServeHTTP(recorder, request); close(done) }()
			deadline := time.Now().Add(3 * time.Second)
			var proof heldSend
			for time.Now().Before(deadline) {
				body, err := os.ReadFile(state)
				if err == nil && json.Unmarshal(body, &proof) == nil {
					break
				}
				time.Sleep(10 * time.Millisecond)
			}
			if proof.Server != scenario.expected || proof.Status != scenario.status || proof.Started <= 0 || time.Now().UnixMilli()-proof.Started > 3000 {
				t.Fatal("successful upstream commit not recorded")
			}
			select {
			case <-done:
				t.Fatal("response escaped before interruption")
			default:
			}
			cancel()
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("interrupted request did not terminate")
			}
			if recorder.Code == scenario.status {
				t.Fatal("held success response leaked")
			}
			retry := httptest.NewRecorder()
			proxy.ServeHTTP(retry, httptest.NewRequest(scenario.method, scenario.path, nil))
			if retry.Code != scenario.status {
				t.Fatal("second send response was held")
			}
		})
	}
}
