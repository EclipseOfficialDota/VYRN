package security

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// Run the real Go and Rust implementations against one another. Synthetic,
// disposable values only; no database, user password, or network is involved.
func TestRustCompatibility(t *testing.T) {
	binary := os.Getenv("VYRN_RUST_BINARY")
	if binary == "" {
		t.Skip("Rust migration interoperability runs in the dedicated Rust CI job")
	}
	run := func(input string, args ...string) (string, bool) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, binary, args...)
		cmd.Stdin = strings.NewReader(input)
		var stderr bytes.Buffer
		cmd.Stderr = &stderr
		out, err := cmd.Output()
		if ctx.Err() != nil {
			t.Fatal("Rust CLI exceeded the per-command time limit")
		}
		if err != nil && (len(out) != 0 || (input != "" && strings.Contains(stderr.String(), input))) {
			t.Fatal("Rust CLI printed input or output while rejecting a command")
		}
		return strings.TrimSuffix(string(out), "\n"), err == nil
	}
	for _, password := range []string{" eight bytes ", "درخت سبز قشنگ", strings.Repeat("é", 512), "line one\nline two", "null\x00byte password", "😀😀😀😀😀😀😀😀"} {
		goHash, err := HashPassword(password)
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := run(password, "verify-password", goHash); !ok {
			t.Fatal("Rust rejected a Go password hash")
		}
		rustHash, ok := run(password, "hash-password")
		if !ok || !VerifyPassword(rustHash, password) {
			t.Fatal("Go rejected a Rust password hash")
		}
		if _, ok := run("wrong password", "verify-password", goHash); ok {
			t.Fatal("Rust accepted a wrong password")
		}
		if _, ok := run(password+"\n", "verify-password", goHash); ok {
			t.Fatal("Rust silently trimmed a password newline")
		}
		for _, malformed := range []string{
			strings.Replace(goHash, "m=65536", "m=99999", 1),
			strings.Replace(goHash, "t=3", "t=9", 1),
			strings.Replace(goHash, "p=1", "p=9", 1),
			goHash[:len(goHash)-1] + "!",
		} {
			if _, ok := run(password, "verify-password", malformed); ok {
				t.Fatal("Rust accepted hostile hash parameters or invalid base64")
			}
		}
	}
	for _, password := range []string{"short", "ééééééé", strings.Repeat("x", 1025), strings.Repeat("x", 1<<20), strings.Repeat("x", 1023) + "é", string([]byte{0xff, 0xfe})} {
		if _, ok := run(password, "hash-password"); ok {
			t.Fatal("Rust accepted an invalid password")
		}
	}
	value, ok := run("", "token")
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if !ok || err != nil || len(decoded) != 32 || len(value) != 43 {
		t.Fatal("Rust token is not compatible with existing sessions")
	}
	got, ok := run(value, "digest")
	if !ok || got != hex.EncodeToString(Digest(value)) {
		t.Fatal("Rust session digest differs from Go")
	}
	if _, ok := run("unused", "serve"); ok {
		t.Fatal("unfinished Rust backend must not start as a production server")
	}
	if _, ok := run("do-not-print-this-secret", "hash-password", "do-not-print-this-secret"); ok {
		t.Fatal("Rust accepted a password as a command-line argument")
	}
}
