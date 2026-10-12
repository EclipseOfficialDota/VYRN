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
	"sync"
	"sync/atomic"
	"time"
)

type heldSend struct {
	Server  string `json:"server_id"`
	Status  int    `json:"status"`
	Started int64  `json:"request_started_ms"`
	Replays int    `json:"replays,omitempty"`
}

var discardedPart = errors.New("discarded committed upload acknowledgement")

// Isolated local fixture: discard only the first committed part-zero response.
func partFaultProxy(target *url.URL, transport http.RoundTripper, state string) http.Handler {
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Transport = transport
	var mu sync.Mutex
	var path string
	var proof heldSend
	part := regexp.MustCompile(`^/v1/media-uploads/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})/parts/0$`)
	proxy.ModifyResponse = func(response *http.Response) error {
		match := part.FindStringSubmatch(response.Request.URL.Path)
		if response.Request.Method != http.MethodPut || match == nil || response.StatusCode < 200 || response.StatusCode >= 300 {
			return nil
		}
		mu.Lock()
		defer mu.Unlock()
		first := path == ""
		if first {
			path = response.Request.URL.Path
			proof = heldSend{Server: match[1], Status: response.StatusCode, Started: time.Now().UnixMilli()}
		} else if path == response.Request.URL.Path {
			proof.Replays++
		} else {
			return nil
		}
		data, _ := json.Marshal(proof)
		if err := os.WriteFile(state, data, 0600); err != nil {
			return err
		}
		if first {
			response.Body.Close()
			return discardedPart
		}
		return nil
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		if errors.Is(err, discardedPart) {
			if hijacker, ok := w.(http.Hijacker); ok {
				if connection, _, failure := hijacker.Hijack(); failure == nil {
					connection.Close()
					return
				}
			}
		}
		http.Error(w, "local response unavailable", http.StatusBadGateway)
	}
	return proxy
}

type requestStart struct{}
type requestClient struct{}

func faultProxy(target *url.URL, transport http.RoundTripper, group, operation, messageID, clientID, state string) http.Handler {
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
		matches := response.Request.URL.Path == path
		if clientID != "" {
			matches = response.Request.Context().Value(requestClient{}) == clientID
		}
		if response.Request.Method != method || !matches || response.StatusCode < 200 || response.StatusCode >= 300 || !held.CompareAndSwap(false, true) {
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
		if clientID != "" && r.Method == http.MethodPost && regexp.MustCompile(`^/v1/conversations/[0-9a-f-]+/messages$`).MatchString(r.URL.Path) {
			body, err := io.ReadAll(io.LimitReader(r.Body, (1<<20)+1))
			if err != nil || len(body) > 1<<20 {
				http.Error(w, "local fault request exceeds limit", http.StatusBadRequest)
				return
			}
			r.Body.Close()
			r.Body = io.NopCloser(bytes.NewReader(body))
			var message struct {
				Client string `json:"client_id"`
			}
			if json.Unmarshal(body, &message) == nil {
				r = r.WithContext(context.WithValue(r.Context(), requestClient{}, message.Client))
			}
		}
		proxy.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), requestStart{}, time.Now().UnixMilli())))
	})
}

func main() {
	group := flag.String("group", "", "disposable local group UUID")
	operation := flag.String("operation", "send", "send, edit, delete or isolated upload-part")
	message := flag.String("message", "", "existing mutation message UUID")
	client := flag.String("client", "", "exact staged send client UUID when conversation is unresolved")
	state := flag.String("state", "", "private local evidence file")
	flag.Parse()
	validID := regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)
	if (!validID.MatchString(*group) && !(*group == "" && *operation == "send" && validID.MatchString(*client))) || (*client != "" && (*operation != "send" || !validID.MatchString(*client))) || *state == "" {
		log.Fatal("local group and evidence path required")
	}
	if *operation != "send" && *operation != "edit" && *operation != "delete" && *operation != "upload-part" {
		log.Fatal("invalid local operation")
	}
	if *operation != "send" && *operation != "upload-part" && !regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`).MatchString(*message) {
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
	var handler http.Handler = faultProxy(target, transport, *group, *operation, *message, *client, *state)
	if *operation == "upload-part" {
		handler = partFaultProxy(target, transport, *state)
	}
	server := &http.Server{Addr: "127.0.0.1:18444", Handler: handler, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}
	log.Fatal(server.ListenAndServeTLS(".local/devtls-server.crt", ".local/devtls-server.key"))
}
