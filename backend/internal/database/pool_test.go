package database

import "testing"

func TestPoolSizingPreservesExplicitLimits(t *testing.T) {
	for _, test := range []struct {
		url      string
		max, min int32
	}{
		{"postgres://user:password@localhost/db", 12, 1},
		{"postgres://user:password@localhost/db?pool_max_conns=4&pool_min_conns=0", 4, 0},
		{"postgres://user:pool_max_conns%3D99@localhost/db", 12, 1},
	} {
		cfg, err := poolConfig(test.url)
		if err != nil || cfg.MaxConns != test.max || cfg.MinConns != test.min {
			t.Fatalf("pool sizing: config=%v error=%v", cfg, err)
		}
	}
	if _, err := poolConfig("postgres://user:password@localhost/db?pool_max_conns=2&pool_min_conns=3"); err == nil {
		t.Fatal("accepted minimum greater than maximum")
	}
}
