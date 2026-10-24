package api

import (
	"io"
	"log/slog"
	"testing"
)

func TestPasswordConcurrencyBudget(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, limit := range []int{0, 1, 2, -1, 3} {
		server, err := New(nil, Config{PasswordConcurrency: limit}, logger)
		if limit < 0 || limit > 2 {
			if err == nil {
				t.Fatal("invalid concurrency accepted")
			}
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		want := limit
		if want == 0 {
			want = 2
		}
		if cap(server.passwordSlots) != want {
			t.Fatal("password work is not bounded by the selected budget")
		}
	}
}
