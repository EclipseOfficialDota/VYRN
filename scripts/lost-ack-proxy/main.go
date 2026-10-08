// Local acceptance helper: hold one successful group-send response until the app is stopped.
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"regexp"
	"sync/atomic"
	"time"
)

type heldSend struct {
	Server  string `json:"server_id"`
	Status  int    `json:"status"`
	Started int64  `json:"request_started_ms"`
}
type requestStart struct{}

func faultProxy(target *url.URL, transport http.RoundTripper, group, operation, messageID, state string) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Transport = transport
	var held atomic.Bool
	method, path := http.MethodPost, "/v1/conversations/"+group+"/messages"
	if operation == "edit" {
		method, path = http.MethodPatch, "/v1/messages/"+messageID
	}
	if operation == "delete" {
		method = http.MethodDelete
	}
	proxy.ModifyResponse = func(response *http.Response) error {
		if response.Request.Method != method || response.Request.URL.Path != path || response.StatusCode < 200 || response.StatusCode >= 300 || !held.CompareAndSwap(false, true) {
			return nil
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		response.Body.Close()
		if err != nil {
			return err
		}
		response.Body = io.NopCloser(bytes.NewReader(body))
		var message struct {
			ID string `json:"id"`
		}
		message.ID = messageID
		if operation == "send" && (json.Unmarshal(body, &message) != nil || message.ID == "") {
			return errors.New("successful send has no server identity")
		}
		proof, _ := json.Marshal(heldSend{Server: message.ID, Status: response.StatusCode, Started: response.Request.Context().Value(requestStart{}).(int64)})
		if err = os.WriteFile(state, proof, 0600); err != nil {
			return err
		}
		select {
		case <-response.Request.Context().Done():
			return response.Request.Context().Err()
		case <-time.After(120 * time.Second):
			return errors.New("held response deadline exceeded")
		}
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxy.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), requestStart{}, time.Now().UnixMilli())))
	})
}

func main() {
	group := flag.String("group", "", "disposable local group UUID")
	operation := flag.String("operation", "send", "send, edit or delete")
	message := flag.String("message", "", "existing mutation message UUID")
	state := flag.String("state", "", "private local evidence file")
	flag.Parse()
	if !regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`).MatchString(*group) || *state == "" {
		log.Fatal("local group and evidence path required")
	}
	if *operation != "send" && *operation != "edit" && *operation != "delete" {
		log.Fatal("invalid local operation")
	}
	if *operation != "send" && !regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`).MatchString(*message) {
		log.Fatal("mutation message UUID required")
	}
	ca, err := os.ReadFile(".local/dev-ca.crt")
	if err != nil {
		log.Fatal("local public CA unavailable")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(ca) {
		log.Fatal("invalid local public CA")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}
	target, _ := url.Parse("https://localhost:18443")
	server := &http.Server{Addr: "127.0.0.1:18444", Handler: faultProxy(target, transport, *group, *operation, *message, *state), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}
	log.Fatal(server.ListenAndServeTLS(".local/devtls-server.crt", ".local/devtls-server.key"))
}
