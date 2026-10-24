package main

import (
	"io"
	"os"
	"regexp"
	"strings"
	"testing"
)

func captureCommand(t *testing.T, command string) string {
	t.Helper()
	file, err := os.CreateTemp(t.TempDir(), "output")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	args, stdout := os.Args, os.Stdout
	defer func() { os.Args, os.Stdout = args, stdout }()
	os.Args, os.Stdout = []string{"vyrn", command}, file
	if err = run(); err != nil {
		t.Fatal("offline configuration generation failed")
	}
	if _, err = file.Seek(0, 0); err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(file)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestOfflineSecretGenerationDoesNotNeedDatabase(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	t.Setenv("VYRN_IMAGE", "example.invalid/vyrn-lite:tested")
	first, second := captureCommand(t, "init-env"), captureCommand(t, "init-env")
	if first == second {
		t.Fatal("generated configuration must use fresh secrets")
	}
	for _, output := range []string{first, second} {
		if !strings.Contains(output, "VYRN_IMAGE=example.invalid/vyrn-lite:tested\nVYRN_DOMAIN=\n") || !regexp.MustCompile(`(?m)^POSTGRES_PASSWORD=[A-Za-z0-9_-]{43}$`).MatchString(output) || !regexp.MustCompile(`(?m)^VYRN_ADMIN_TOTP_SECRET=[A-Z2-7]{32}$`).MatchString(output) {
			t.Fatal("generated settings must contain a selected image, unset domain and valid secrets")
		}
	}
	if !regexp.MustCompile(`^[A-Za-z0-9_-]{43}\n$`).MatchString(captureCommand(t, "token")) {
		t.Fatal("invalid bootstrap token")
	}
}
