package updates

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/url"
	"time"
)

type Manifest struct {
	VersionCode    int               `json:"version_code"`
	VersionName    string            `json:"version_name"`
	MinVersionCode int               `json:"minimum_supported_version"`
	Force          bool              `json:"forced"`
	APKURL         string            `json:"apk_url"`
	SHA256         string            `json:"sha256"`
	Size           int64             `json:"size"`
	Changelog      map[string]string `json:"changelog"`
	ExpiresAt      time.Time         `json:"expires_at"`
}

// Sign exact UTF-8 bytes. Clients verify these bytes before decoding; no implicit JSON canonicalization.
type Envelope struct {
	Payload   string `json:"payload_base64"`
	Signature string `json:"signature_base64"`
}

func Sign(m Manifest, key ed25519.PrivateKey) (Envelope, error) {
	if len(key) != ed25519.PrivateKeySize {
		return Envelope{}, errors.New("invalid_signing_key")
	}
	body, err := json.Marshal(m)
	if err != nil {
		return Envelope{}, err
	}
	return Envelope{base64.StdEncoding.EncodeToString(body), base64.StdEncoding.EncodeToString(ed25519.Sign(key, body))}, nil
}
func Verify(e Envelope, key ed25519.PublicKey, current int, now time.Time) (Manifest, error) {
	var m Manifest
	if len(key) != ed25519.PublicKeySize || len(e.Payload) > 256<<10 {
		return m, errors.New("invalid_manifest")
	}
	body, err := base64.StdEncoding.DecodeString(e.Payload)
	if err != nil {
		return m, errors.New("invalid_manifest")
	}
	signature, err := base64.StdEncoding.DecodeString(e.Signature)
	if err != nil || !ed25519.Verify(key, body, signature) {
		return m, errors.New("invalid_signature")
	}
	if json.Unmarshal(body, &m) != nil {
		return m, errors.New("invalid_manifest")
	}
	endpoint, err := url.Parse(m.APKURL)
	digest, e2 := hex.DecodeString(m.SHA256)
	if err != nil || endpoint.Scheme != "https" || endpoint.Hostname() != "updates.vyrn.eclipseofficial.xyz" || endpoint.User != nil || endpoint.Port() != "" || e2 != nil || len(digest) != sha256.Size || m.VersionCode <= current || m.MinVersionCode < 1 || m.MinVersionCode > m.VersionCode || m.Size <= 0 || !m.ExpiresAt.After(now) {
		return m, errors.New("invalid_or_expired_manifest")
	}
	return m, nil
}
