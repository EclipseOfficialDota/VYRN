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

func faultProxy(target *url.URL, transport http.RoundTripper, group, state string) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Transport = transport
	var held atomic.Bool
	proxy.ModifyResponse = func(response *http.Response) error {
		if response.Request.Method != http.MethodPost || response.Request.URL.Path != "/v1/conversations/"+group+"/messages" || response.StatusCode < 200 || response.StatusCode >= 300 || !held.CompareAndSwap(false, true) {
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
		if json.Unmarshal(body, &message) != nil || message.ID == "" {
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
	state := flag.String("state", "", "private local evidence file")
	flag.Parse()
	if !regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`).MatchString(*group) || *state == "" {
		log.Fatal("local group and evidence path required")
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
	server := &http.Server{Addr: "127.0.0.1:18444", Handler: faultProxy(target, transport, *group, *state), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}
	log.Fatal(server.ListenAndServeTLS(".local/devtls-server.crt", ".local/devtls-server.key"))
}
