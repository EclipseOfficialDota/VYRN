package api

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"
	"testing"
)

func TestAvatarImagesMetadataAndAccess(t *testing.T) {
	f := setup(t)
	a, b := f.register("avatar-owner@test.invalid"), f.register("avatar-viewer@test.invalid")
	raw := func(method, path, token string, data []byte, header string, want int) ([]byte, http.Header) {
		t.Helper()
		req, err := http.NewRequest(method, f.http.URL+path, bytes.NewReader(data))
		if err != nil {
			t.Fatal(err)
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		if header != "" {
			req.Header.Set("Range", header)
		}
		resp, err := f.http.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(resp.Body)
		if err != nil || resp.StatusCode != want {
			t.Fatalf("avatar HTTP status: got %d, want %d", resp.StatusCode, want)
		}
		return body, resp.Header
	}
	img := image.NewRGBA(image.Rect(0, 0, 64, 48))
	img.Set(3, 4, color.RGBA{R: 180, G: 50, B: 30, A: 255})
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, img); err != nil {
		t.Fatal(err)
	}
	data := append([]byte(nil), encoded.Bytes()...)
	raw("POST", "/v1/media?kind=avatar", "", data, "", 401)
	response, _ := raw("POST", "/v1/media?kind=avatar", a.Access, data, "", 201)
	var upload struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(response, &upload) != nil || upload.ID == "" {
		t.Fatal("avatar upload response")
	}
	f.request("GET", "/v1/users/"+a.User+"/avatar", a.Access, nil, 404)
	raw("GET", "/v1/media/"+upload.ID, b.Access, nil, "", 404)
	f.request("PATCH", "/v1/me", b.Access, map[string]any{"avatar_id": upload.ID}, 400)
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": upload.ID}, 200)
	f.request("GET", "/v1/users/"+a.User+"/avatar", "", nil, 401)
	var metadata map[string]any
	if json.Unmarshal(f.request("GET", "/v1/users/"+a.User+"/avatar", b.Access, nil, 200), &metadata) != nil || len(metadata) != 6 || metadata["id"] != upload.ID || metadata["width"] != float64(64) || metadata["height"] != float64(48) || metadata["size"] != float64(len(data)) || metadata["content_type"] != "image/png" || metadata["date"] == nil {
		t.Fatal("actual public avatar metadata")
	}
	body, headers := raw("GET", "/v1/media/"+upload.ID, b.Access, nil, "", 200)
	if !bytes.Equal(body, data) || headers.Get("Content-Type") != "image/png" {
		t.Fatal("avatar bytes and detected type")
	}
	body, headers = raw("GET", "/v1/media/"+upload.ID, b.Access, nil, "bytes=3-9", 206)
	if !bytes.Equal(body, data[3:10]) || headers.Get("Content-Range") == "" {
		t.Fatal("avatar range")
	}
	raw("GET", "/v1/media/"+upload.ID, b.Access, nil, "bytes=999999-", 416)
	encoded.Reset()
	if err := jpeg.Encode(&encoded, img, nil); err != nil {
		t.Fatal(err)
	}
	secondResponse, _ := raw("POST", "/v1/media?kind=avatar", a.Access, encoded.Bytes(), "", 201)
	var second struct {
		ID string `json:"id"`
	}
	if json.Unmarshal(secondResponse, &second) != nil {
		t.Fatal("second avatar")
	}
	raw("POST", "/v1/media?kind=avatar", a.Access, []byte("not an image"), "", 400)
	raw("POST", "/v1/media?kind=avatar", a.Access, data[:len(data)/2], "", 400)
	encoded.Reset()
	if err := png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 4097, 1))); err != nil {
		t.Fatal(err)
	}
	raw("POST", "/v1/media?kind=avatar", a.Access, encoded.Bytes(), "", 400)
	var count int
	if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM media WHERE owner_id=$1`, a.User).Scan(&count); err != nil || count != 2 {
		t.Fatal("failed avatar uploads must leave no rows")
	}
	f.request("PATCH", "/v1/me", a.Access, map[string]any{"avatar_id": second.ID}, 200)
	f.request("DELETE", "/v1/me/avatar/"+upload.ID, a.Access, nil, 409)
	f.request("GET", "/v1/users/"+a.User+"/avatar", b.Access, nil, 200)
	f.request("DELETE", "/v1/me/avatar/bad", a.Access, nil, 400)
	f.request("DELETE", "/v1/me/avatar/"+second.ID, "", nil, 401)
	f.request("DELETE", "/v1/me/avatar/"+second.ID, a.Access, nil, 204)
	f.request("DELETE", "/v1/me/avatar/"+second.ID, a.Access, nil, 204)
	f.request("GET", "/v1/users/"+a.User+"/avatar", b.Access, nil, 404)
	raw("GET", "/v1/media/"+upload.ID, b.Access, nil, "", 404)
	raw("GET", "/v1/media/"+upload.ID, a.Access, nil, "", 200)
}
