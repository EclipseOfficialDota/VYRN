package security

import (
	"encoding/base64"
	"encoding/hex"
	"os"
	"os/exec"
	"strings"
	"testing"
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
		cmd := exec.Command(binary, args...)
		cmd.Stdin = strings.NewReader(input)
		out, err := cmd.Output()
		return strings.TrimSuffix(string(out), "\n"), err == nil
	}
	for _, password := range []string{" eight bytes ", "درخت سبز قشنگ", strings.Repeat("é", 512)} {
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
	}
	for _, password := range []string{"short", strings.Repeat("x", 1025), string([]byte{0xff, 0xfe})} {
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
}
