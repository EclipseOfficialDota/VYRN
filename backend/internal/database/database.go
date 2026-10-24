package database

import (
	"context"
	"crypto/sha256"
	"embed"
	"fmt"
	"io/fs"
	"net/url"
	"sort"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrations embed.FS

func poolConfig(connection string) (*pgxpool.Config, error) {
	cfg, err := pgxpool.ParseConfig(connection)
	if err != nil {
		return nil, fmt.Errorf("invalid database configuration")
	}
	parsed, _ := url.Parse(connection)
	query := url.Values{}
	if parsed != nil && (parsed.Scheme == "postgres" || parsed.Scheme == "postgresql") {
		query = parsed.Query()
	}
	if !query.Has("pool_max_conns") {
		cfg.MaxConns = 12
	}
	if !query.Has("pool_min_conns") {
		cfg.MinConns = 1
	}
	if cfg.MinConns > cfg.MaxConns {
		return nil, fmt.Errorf("minimum pool size exceeds maximum")
	}
	return cfg, nil
}

func Open(ctx context.Context, connection string) (*pgxpool.Pool, error) {
	cfg, err := poolConfig(connection)
	if err != nil {
		return nil, err
	}
	return pgxpool.NewWithConfig(ctx, cfg)
}

func Migrate(ctx context.Context, db *pgxpool.Pool) error {
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(86142001); CREATE TABLE IF NOT EXISTS schema_migrations(name text PRIMARY KEY, checksum bytea NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	entries, err := fs.Glob(migrations, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(entries)
	for _, name := range entries {
		body, err := migrations.ReadFile(name)
		if err != nil {
			return err
		}
		sum := sha256.Sum256(body)
		var recorded []byte
		err = tx.QueryRow(ctx, `SELECT checksum FROM schema_migrations WHERE name=$1`, name).Scan(&recorded)
		if err == nil {
			if string(recorded) != string(sum[:]) {
				return fmt.Errorf("migration checksum changed: %s", name)
			}
			continue
		}
		if err != pgx.ErrNoRows {
			return err
		}
		if _, err = tx.Exec(ctx, string(body)); err != nil {
			return fmt.Errorf("migration %s failed: %w", name, err)
		}
		if _, err = tx.Exec(ctx, `INSERT INTO schema_migrations(name,checksum) VALUES($1,$2)`, name, sum[:]); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}
