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

func TestCommittedUploadPartHeldUntilProcessInterruption(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer upstream.Close()
	target, _ := url.Parse(upstream.URL)
	state := filepath.Join(t.TempDir(), "held-part.json")
	proxy := partFaultProxy(target, http.DefaultTransport, state, true, 0)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request := httptest.NewRequest("PUT", "/v1/media-uploads/11111111-1111-4111-8111-111111111111/parts/0", strings.NewReader("part")).WithContext(ctx)
	recorder := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { proxy.ServeHTTP(recorder, request); close(done) }()
	deadline := time.Now().Add(3 * time.Second)
	var proof heldSend
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(state)
		if err == nil && json.Unmarshal(data, &proof) == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if proof.Status != 204 || proof.Server != "11111111-1111-4111-8111-111111111111" {
		t.Fatal("committed part proof missing")
	}
	select {
	case <-done:
		t.Fatal("part acknowledgement escaped before interruption")
	default:
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("held upload did not stop after cancellation")
	}
	if recorder.Code == 204 {
		t.Fatal("held success leaked")
	}
}

func TestCommittedSecondPartHeldAfterFirstAcknowledgement(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer upstream.Close()
	target, _ := url.Parse(upstream.URL)
	state := filepath.Join(t.TempDir(), "held-part.json")
	proxy := partFaultProxy(target, http.DefaultTransport, state, true, 1)
	first := httptest.NewRecorder()
	proxy.ServeHTTP(first, httptest.NewRequest("PUT", "/v1/media-uploads/11111111-1111-4111-8111-111111111111/parts/0", strings.NewReader("first")))
	if first.Code != 204 {
		t.Fatal("first part acknowledgement did not pass")
	}
	if _, err := os.Stat(state); !os.IsNotExist(err) {
		t.Fatal("first part armed second-part hold")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request := httptest.NewRequest("PUT", "/v1/media-uploads/11111111-1111-4111-8111-111111111111/parts/1", strings.NewReader("part")).WithContext(ctx)
	recorder := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { proxy.ServeHTTP(recorder, request); close(done) }()
	deadline := time.Now().Add(3 * time.Second)
	var proof heldSend
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(state)
		if err == nil && json.Unmarshal(data, &proof) == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if proof.Status != 204 || proof.Server != "11111111-1111-4111-8111-111111111111" {
		t.Fatal("committed part proof missing")
	}
	select {
	case <-done:
		t.Fatal("part acknowledgement escaped before interruption")
	default:
	}
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("held upload did not stop after cancellation")
	}
	if recorder.Code == 204 {
		t.Fatal("held success leaked")
	}
}

func TestCommittedUploadAckDiscardedAndReplayObserved(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("failed") == "1" {
			w.WriteHeader(503)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	target, _ := url.Parse(upstream.URL)
	state := filepath.Join(t.TempDir(), "part.json")
	front := httptest.NewServer(partFaultProxy(target, http.DefaultTransport, state, false, 0))
	defer front.Close()
	path := "/v1/media-uploads/00000000-0000-0000-0000-000000000001/parts/0"
	for _, unrelated := range []struct {
		method, path string
		status       int
	}{{http.MethodGet, path, 204}, {http.MethodPut, strings.TrimSuffix(path, "0") + "1", 204}, {http.MethodPut, path + "?failed=1", 503}} {
		request, _ := http.NewRequest(unrelated.method, front.URL+unrelated.path, strings.NewReader("same bytes"))
		response, err := http.DefaultClient.Do(request)
		if err != nil || response.StatusCode != unrelated.status {
			t.Fatal("unrelated or failed response changed")
		}
		response.Body.Close()
		if _, err := os.Stat(state); !os.IsNotExist(err) {
			t.Fatal("unrelated or failed response armed fault")
		}
	}
	for attempt := 0; attempt < 2; attempt++ {
		request, _ := http.NewRequest(http.MethodPut, front.URL+path, strings.NewReader("same bytes"))
		response, err := http.DefaultClient.Do(request)
		if attempt == 0 {
			if err == nil {
				response.Body.Close()
				t.Fatal("committed acknowledgement escaped")
			}
		} else {
			if err != nil || response.StatusCode != http.StatusNoContent {
				t.Fatal("replay did not pass")
			}
			response.Body.Close()
		}
	}
	data, err := os.ReadFile(state)
	var proof heldSend
	if err != nil || json.Unmarshal(data, &proof) != nil || proof.Status != 204 || proof.Replays != 1 || proof.Server != "00000000-0000-0000-0000-000000000001" {
		t.Fatal("missing commit and replay evidence")
	}
}

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
