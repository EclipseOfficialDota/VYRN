package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"testing"

	"github.com/google/uuid"
)

func TestIntegrationCancelSend(t *testing.T) {
	f := setup(t)
	a, b, outsider := f.register("cancel-a@test.invalid"), f.register("cancel-b@test.invalid"), f.register("cancel-outsider@test.invalid")
	group := f.conversation(a, "group", b.User)
	otherGroup := f.conversation(a, "group", b.User)
	client := uuid.NewString()
	path := "/v1/conversations/" + group + "/sends/" + client + "/cancel"
	f.request("PUT", path, outsider.Access, nil, 404)
	f.request("PUT", path, "", nil, 401)
	for i := 0; i < 2; i++ {
		var result map[string]string
		if err := json.Unmarshal(f.request("PUT", path, a.Access, nil, 200), &result); err != nil || result["status"] != "cancelled" {
			t.Fatal("cancellation did not persist idempotently")
		}
	}
	input := map[string]any{"client_id": client, "payload": map[string]string{"text": "Cancelled"}, "encrypted": false}
	f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, input, 409)
	f.request("POST", "/v1/conversations/"+otherGroup+"/messages", a.Access, input, 409)
	f.request("PUT", "/v1/conversations/"+otherGroup+"/sends/"+client+"/cancel", a.Access, nil, 409)
	// The client-ID namespace belongs to the authenticated sender, not other members.
	f.request("POST", "/v1/conversations/"+group+"/messages", b.Access, input, 201)
	committedClient := uuid.NewString()
	input["client_id"] = committedClient
	var sent map[string]string
	_ = json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, input, 201), &sent)
	var outcome map[string]string
	_ = json.Unmarshal(f.request("PUT", "/v1/conversations/"+group+"/sends/"+committedClient+"/cancel", a.Access, nil, 200), &outcome)
	if outcome["status"] != "committed" || outcome["message_id"] != sent["id"] {
		t.Fatal("committed message must be reconciled, not reported cancelled")
	}
	f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, input, 200)
	f.request("PUT", "/v1/conversations/"+group+"/sends/invalid/cancel", a.Access, nil, 400)

	type response struct {
		send   bool
		status int
		body   map[string]string
		err    error
	}
	for iteration := 0; iteration < 20; iteration++ {
		id := uuid.NewString()
		start, results := make(chan struct{}), make(chan response, 2)
		for _, send := range []bool{true, false} {
			go func(send bool) {
				<-start
				method, url := "PUT", f.http.URL+"/v1/conversations/"+group+"/sends/"+id+"/cancel"
				var body io.Reader
				if send {
					method, url = "POST", f.http.URL+"/v1/conversations/"+group+"/messages"
					payload, _ := json.Marshal(map[string]any{"client_id": id, "payload": map[string]string{"text": "Race"}, "encrypted": false})
					body = bytes.NewReader(payload)
				}
				req, err := http.NewRequest(method, url, body)
				if err != nil {
					results <- response{send: send, err: err}
					return
				}
				req.Header.Set("Authorization", "Bearer "+a.Access)
				req.Header.Set("Content-Type", "application/json")
				res, err := f.http.Client().Do(req)
				if err != nil {
					results <- response{send: send, err: err}
					return
				}
				defer res.Body.Close()
				var decoded map[string]string
				err = json.NewDecoder(res.Body).Decode(&decoded)
				results <- response{send: send, status: res.StatusCode, body: decoded, err: err}
			}(send)
		}
		close(start)
		var sendResult, cancelResult response
		for i := 0; i < 2; i++ {
			result := <-results
			if result.err != nil {
				t.Fatal(result.err)
			}
			if result.send {
				sendResult = result
			} else {
				cancelResult = result
			}
		}
		var count int
		if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM messages WHERE sender_id=$1 AND client_id=$2`, a.User, id).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if cancelResult.status != 200 {
			t.Fatal("cancellation race failed", cancelResult.status)
		}
		switch cancelResult.body["status"] {
		case "cancelled":
			if sendResult.status != 409 || sendResult.body["error"] != "send_cancelled" || count != 0 {
				t.Fatal("send escaped cancellation fence", iteration)
			}
		case "committed":
			if sendResult.status != 201 || count != 1 || cancelResult.body["message_id"] != sendResult.body["id"] {
				t.Fatal("committed outcome was not reconciled", iteration)
			}
		default:
			t.Fatal(fmt.Sprintf("unexpected cancellation outcome in race %d", iteration))
		}
	}
}
