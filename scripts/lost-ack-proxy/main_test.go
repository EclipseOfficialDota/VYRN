package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCommittedResponseHeldOnce(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		w.Write([]byte(`{"id":"committed-message"}`))
	}))
	defer upstream.Close()
	target, _ := url.Parse(upstream.URL)
	state := filepath.Join(t.TempDir(), "held.json")
	proxy := faultProxy(target, http.DefaultTransport, "group", state)
	other := httptest.NewRecorder()
	proxy.ServeHTTP(other, httptest.NewRequest(http.MethodPost, "/v1/conversations/other/messages", nil))
	if other.Code != http.StatusCreated {
		t.Fatal("unrelated send changed")
	}
	if _, err := os.Stat(state); !os.IsNotExist(err) {
		t.Fatal("unrelated send armed the fault")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request := httptest.NewRequest(http.MethodPost, "/v1/conversations/group/messages", nil).WithContext(ctx)
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
	if proof.Server != "committed-message" || proof.Status != http.StatusCreated || proof.Started <= 0 || time.Now().UnixMilli()-proof.Started > 3000 {
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
	if recorder.Code == http.StatusCreated {
		t.Fatal("held success response leaked")
	}
	retry := httptest.NewRecorder()
	proxy.ServeHTTP(retry, httptest.NewRequest(http.MethodPost, "/v1/conversations/group/messages", nil))
	if retry.Code != http.StatusCreated {
		t.Fatal("second send response was held")
	}
}
