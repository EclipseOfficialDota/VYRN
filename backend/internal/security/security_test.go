package security

import "testing"

func TestPasswords(t *testing.T) {
	hash, err := HashPassword("درخت سبز قشنگ")
	if err != nil || !VerifyPassword(hash, "درخت سبز قشنگ") {
		t.Fatal("Argon2id round trip failed", err)
	}
	if VerifyPassword(hash, "wrong password") || VerifyPassword("$argon2id$v=19$m=999999999,t=3,p=1$x$y", "wrong password") {
		t.Fatal("invalid password/hash accepted")
	}
	other, _ := HashPassword("درخت سبز قشنگ")
	if other == hash {
		t.Fatal("salt reused")
	}
	if ValidPassword("1234567") || !ValidPassword("12345678") {
		t.Fatal("length rules")
	}
}

func TestEmail(t *testing.T) {
	for _, value := range []string{" USER@Example.com ", "user@example.com"} {
		got, err := NormalizeEmail(value)
		if err != nil || got != "user@example.com" {
			t.Fatalf("normalization: %q", got)
		}
	}
	for _, value := range []string{"User <u@example.com>", "u\n@example.com", "not email", ""} {
		if _, err := NormalizeEmail(value); err == nil {
			t.Fatalf("accepted %q", value)
		}
	}
}

func TestTokens(t *testing.T) {
	a, _ := Token()
	b, _ := Token()
	if a == b || len(a) != 43 || len(Digest(a)) != 32 {
		t.Fatal("token entropy or encoding")
	}
}
