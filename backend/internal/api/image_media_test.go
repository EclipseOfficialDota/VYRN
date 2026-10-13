package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"image"
	"image/jpeg"
	"image/png"
	"testing"

	"github.com/google/uuid"
)

func TestImageMediaDimensionsAndValidation(t *testing.T) {
	for _, format := range []string{"png", "jpeg"} {
		for _, resumable := range []bool{false, true} {
			name := format + "/direct"
			if resumable {
				name = format + "/resumable"
			}
			t.Run(name, func(t *testing.T) {
				f := setup(t)
				a, b, c := f.register("image-owner@test.invalid"), f.register("image-peer@test.invalid"), f.register("image-outsider@test.invalid")
				group := f.conversation(a, "group", b.User)
				var encoded bytes.Buffer
				img := image.NewRGBA(image.Rect(0, 0, 64, 48))
				var err error
				if format == "png" {
					err = png.Encode(&encoded, img)
				} else {
					err = jpeg.Encode(&encoded, img, nil)
				}
				if err != nil {
					t.Fatal(err)
				}
				upload := func(data []byte, want int) []byte {
					if !resumable {
						return uploadRaw(f, "POST", "/v1/media?kind=image&conversation_id="+group, a.Access, data, want)
					}
					path := "/v1/media-uploads/" + uuid.NewString()
					uploadRaw(f, "PUT", path+"/parts/0", a.Access, data, 204)
					checksum := md5.Sum(data)
					return f.request("POST", path+"/complete", a.Access, map[string]any{"conversation_id": group, "kind": "image", "filename": "photo." + format, "part_count": 1, "md5": hex.EncodeToString(checksum[:])}, want)
				}
				data := encoded.Bytes()
				// DecodeConfig still succeeds; complete image decoding must reject this.
				truncated := data[:len(data)-20]
				if _, _, err := image.DecodeConfig(bytes.NewReader(truncated)); err != nil {
					t.Fatal("fixture must retain its image header", err)
				}
				upload(truncated, 400)
				var completed struct {
					ID string `json:"id"`
				}
				if json.Unmarshal(upload(data, 201), &completed) != nil || completed.ID == "" {
					t.Fatal("image completion")
				}
				metadataPath := "/v1/media/" + completed.ID + "/metadata"
				f.request("GET", metadataPath, b.Access, nil, 404)
				var sent map[string]string
				if json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"photo"}`), MediaID: &completed.ID}, 201), &sent) != nil {
					t.Fatal("photo message")
				}
				var metadata map[string]any
				hash := sha256.Sum256(data)
				if json.Unmarshal(f.request("GET", metadataPath, b.Access, nil, 200), &metadata) != nil || metadata["width"] != float64(64) || metadata["height"] != float64(48) || metadata["content_type"] != "image/"+format || metadata["sha256"] != hex.EncodeToString(hash[:]) || metadata["storage_key"] != nil {
					t.Fatal("photo descriptor")
				}
				if !bytes.Equal(f.request("GET", "/v1/media/"+completed.ID, b.Access, nil, 200), data) {
					t.Fatal("photo bytes")
				}
				f.request("GET", metadataPath, c.Access, nil, 404)
				var count int
				if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM media WHERE owner_id=$1`, a.User).Scan(&count); err != nil || count != 1 {
					t.Fatal("invalid photo must leave no published media")
				}
				if _, err := f.db.Exec(context.Background(), `UPDATE messages SET deleted_at=now() WHERE id=$1`, sent["id"]); err != nil {
					t.Fatal(err)
				}
				f.request("GET", metadataPath, b.Access, nil, 404)
				f.request("GET", "/v1/media/"+completed.ID, b.Access, nil, 404)
			})
		}
	}
}
