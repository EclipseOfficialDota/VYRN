package updates

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

func TestSignedManifest(t *testing.T) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	m := Manifest{VersionCode: 2, MinVersionCode: 1, APKURL: "https://updates.vyrn.eclipseofficial.xyz/vyrn.apk", SHA256: strings.Repeat("ab", 32), Size: 5000, ExpiresAt: now.Add(time.Hour)}
	e, err := Sign(m, key)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = Verify(e, pub, 1, now); err != nil {
		t.Fatal(err)
	}
	if _, err = Verify(e, pub, 2, now); err == nil {
		t.Fatal("rollback accepted")
	}
	if _, err = Verify(e, pub, 1, now.Add(2*time.Hour)); err == nil {
		t.Fatal("expired manifest accepted")
	}
	e.Payload = base64.StdEncoding.EncodeToString([]byte(`{"forced":true}`))
	if _, err = Verify(e, pub, 1, now); err == nil {
		t.Fatal("tampered manifest accepted")
	}
	m.APKURL = "https://evil.example/vyrn.apk"
	e, _ = Sign(m, key)
	if _, err = Verify(e, pub, 1, now); err == nil {
		t.Fatal("external download accepted")
	}
}
