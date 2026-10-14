package api

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/google/uuid"
)

func voicePage(sequence uint32, flags byte, granule uint64, packets ...[]byte) []byte {
	header := make([]byte, 27)
	copy(header, "OggS")
	header[5] = flags
	binary.LittleEndian.PutUint64(header[6:14], granule)
	binary.LittleEndian.PutUint32(header[14:18], 123)
	binary.LittleEndian.PutUint32(header[18:22], sequence)
	var laces, body []byte
	for _, packet := range packets {
		for len(packet) >= 255 {
			laces = append(laces, 255)
			body = append(body, packet[:255]...)
			packet = packet[255:]
		}
		laces = append(laces, byte(len(packet)))
		body = append(body, packet...)
	}
	header[26] = byte(len(laces))
	binary.LittleEndian.PutUint32(header[22:26], oggCRC(header, laces, body))
	return append(append(header, laces...), body...)
}

func voiceFixture() []byte {
	head := append([]byte("OpusHead"), 1, 1, 56, 1, 128, 187, 0, 0, 0, 0, 0) // preskip312, mono48kHz
	tags := append([]byte("OpusTags"), make([]byte, 8)...)
	packets := make([][]byte, 50)
	for i := range packets {
		packets[i] = []byte{0xf8, 0xff, 0xfe}
	} // valid20ms Opus silence
	return append(append(voicePage(0, 2, 0, head), voicePage(1, 0, 0, tags)...), voicePage(2, 4, 48000, packets...)...)
}

func TestVoiceEnvelopeValidation(t *testing.T) {
	valid := voiceFixture()
	path := filepath.Join(t.TempDir(), "voice.ogg")
	for _, scenario := range []struct {
		name  string
		data  []byte
		valid bool
	}{
		{"valid", valid, true}, {"truncated", valid[:len(valid)-1], false}, {"missing_eos", valid[:len(voicePage(0, 2, 0, append([]byte("OpusHead"), 1, 1, 56, 1, 128, 187, 0, 0, 0, 0, 0)))], false},
		{"trailing", append(bytes.Clone(valid), 0), false}, {"not_audio", []byte("arbitrary file"), false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			if err := os.WriteFile(path, scenario.data, 0600); err != nil {
				t.Fatal(err)
			}
			duration, err := voiceDuration(path)
			if scenario.valid {
				if err != nil || duration != 994 {
					t.Fatalf("duration=%d err=%v", duration, err)
				}
			} else if err == nil {
				t.Fatal("invalid voice accepted")
			}
		})
	}
	bad := bytes.Clone(valid)
	bad[len(bad)-1] ^= 1
	os.WriteFile(path, bad, 0600)
	if _, err := voiceDuration(path); err == nil {
		t.Fatal("corrupt page accepted")
	}
	for _, packet := range [][]byte{{}, {0xfb}, {0xfb, 0}, {0xfb, 49}, {0xf9, 1}, {0xfa, 252}, {0xfa, 10, 1}, {0xfb, 0x82, 255}, {0xfb, 0x42, 255}} {
		if _, err := opusPacketSamples(packet); err == nil {
			t.Fatalf("malformed framing accepted %x", packet)
		}
	}
}

func TestNativeVoiceEncoderCompatibility(t *testing.T) {
	for _, scenario := range []struct {
		file     string
		duration int64
	}{{"voice-native-0.ogg", 1000}, {"voice-native-480.ogg", 1010}, {"voice-native-959.ogg", 1020}, {"voice-native-decoded-0.ogg", 1000}, {"voice-native-decoded-480.ogg", 1010}, {"voice-native-decoded-959.ogg", 1020}} {
		t.Run(scenario.file, func(t *testing.T) {
			duration, err := voiceDuration(filepath.Join("testdata", scenario.file))
			if err != nil || duration != scenario.duration {
				t.Fatalf("native voice duration=%d want=%d err=%v", duration, scenario.duration, err)
			}
		})
	}
}

func TestVoiceMediaDurationAndAccess(t *testing.T) {
	for _, sample := range []struct {
		name, file string
		duration   int64
	}{{"silence", "", 994}, {"native", "voice-native-480.ogg", 1010}} {
		for _, resumable := range []bool{false, true} {
			name := sample.name + "/direct"
			if resumable {
				name = sample.name + "/resumable"
			}
			t.Run(name, func(t *testing.T) {
				f := setup(t)
				a, b, c := f.register("voice-owner@test.invalid"), f.register("voice-peer@test.invalid"), f.register("voice-outsider@test.invalid")
				group := f.conversation(a, "group", b.User)
				upload := func(data []byte, want int) []byte {
					if !resumable {
						return uploadRaw(f, "POST", "/v1/media?kind=voice&conversation_id="+group, a.Access, data, want)
					}
					path := "/v1/media-uploads/" + uuid.NewString()
					uploadRaw(f, "PUT", path+"/parts/0", a.Access, data, 204)
					checksum := md5.Sum(data)
					return f.request("POST", path+"/complete", a.Access, map[string]any{"conversation_id": group, "kind": "voice", "filename": "voice.ogg", "part_count": 1, "md5": hex.EncodeToString(checksum[:])}, want)
				}
				data := voiceFixture()
				if sample.file != "" {
					var err error
					data, err = os.ReadFile(filepath.Join("testdata", sample.file))
					if err != nil {
						t.Fatal(err)
					}
				}
				upload(data[:len(data)-1], 400)
				upload([]byte("not an Opus recording"), 400)
				var completed struct {
					ID string `json:"id"`
				}
				if json.Unmarshal(upload(data, 201), &completed) != nil || completed.ID == "" {
					t.Fatal("voice completion")
				}
				path := "/v1/media/" + completed.ID + "/metadata"
				f.request("GET", path, b.Access, nil, 404)
				var sent map[string]string
				if json.Unmarshal(f.request("POST", "/v1/conversations/"+group+"/messages", a.Access, messageInput{ClientID: uuid.NewString(), Payload: json.RawMessage(`{"text":"voice"}`), MediaID: &completed.ID}, 201), &sent) != nil {
					t.Fatal("voice message")
				}
				var metadata map[string]any
				hash := sha256.Sum256(data)
				if json.Unmarshal(f.request("GET", path, b.Access, nil, 200), &metadata) != nil || metadata["duration_ms"] != float64(sample.duration) || metadata["content_type"] != "audio/ogg" || metadata["sha256"] != hex.EncodeToString(hash[:]) || metadata["storage_key"] != nil {
					t.Fatal("voice descriptor")
				}
				if !bytes.Equal(f.request("GET", "/v1/media/"+completed.ID, b.Access, nil, 200), data) {
					t.Fatal("voice bytes")
				}
				f.request("GET", path, c.Access, nil, 404)
				var count int
				if err := f.db.QueryRow(context.Background(), `SELECT count(*) FROM media WHERE owner_id=$1`, a.User).Scan(&count); err != nil || count != 1 {
					t.Fatal("invalid voice left published media")
				}
				if _, err := f.db.Exec(context.Background(), `UPDATE messages SET deleted_at=now() WHERE id=$1`, sent["id"]); err != nil {
					t.Fatal(err)
				}
				f.request("GET", path, b.Access, nil, 404)
				f.request("GET", "/v1/media/"+completed.ID, b.Access, nil, 404)
			})
		}

	}
}
