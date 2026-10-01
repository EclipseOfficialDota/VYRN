package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"xyz.eclipseofficial/vyrn/internal/updates"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: vyrn-update input-manifest.json output-envelope.json; set VYRN_UPDATE_PRIVATE_KEY_FILE")
		os.Exit(1)
	}
	if run() != nil {
		fmt.Fprintln(os.Stderr, "Update signing failed; no key material printed")
		os.Exit(1)
	}
}
func run() error {
	raw, err := os.ReadFile(os.Getenv("VYRN_UPDATE_PRIVATE_KEY_FILE"))
	if err != nil {
		return err
	}
	key, err := base64.StdEncoding.DecodeString(string(raw))
	if err != nil {
		return err
	}
	if len(key) != ed25519.PrivateKeySize {
		return fmt.Errorf("invalid key")
	}
	body, err := os.ReadFile(os.Args[1])
	if err != nil {
		return err
	}
	var m updates.Manifest
	if err = json.Unmarshal(body, &m); err != nil {
		return err
	}
	envelope, err := updates.Sign(m, ed25519.PrivateKey(key))
	if err != nil {
		return err
	}
	result, err := json.MarshalIndent(envelope, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(os.Args[2], result, 0644)
}
