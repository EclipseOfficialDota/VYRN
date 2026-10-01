package main

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/google/uuid"
	"xyz.eclipseofficial/vyrn/internal/api"
	"xyz.eclipseofficial/vyrn/internal/database"
	"xyz.eclipseofficial/vyrn/internal/security"
)

func main() {
	if err := run(); err != nil {
		slog.Error("startup_failed", "category", err.Error())
		os.Exit(1)
	}
}
func run() error {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		return errors.New("DATABASE_URL is required")
	}
	db, err := database.Open(ctx, url)
	if err != nil {
		return errors.New("database_configuration")
	}
	defer db.Close()
	if err = database.Migrate(ctx, db); err != nil {
		return errors.New("database_migration")
	}
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "migrate":
			return nil
		case "bootstrap":
			email, e := security.NormalizeEmail(os.Getenv("VYRN_BOOTSTRAP_EMAIL"))
			if e != nil {
				return errors.New("bootstrap_email_required")
			}
			hash, e := security.HashPassword(os.Getenv("VYRN_BOOTSTRAP_PASSWORD"))
			if e != nil {
				return errors.New("bootstrap_password_required")
			}
			invite := os.Getenv("VYRN_BOOTSTRAP_INVITE")
			if len(invite) < 32 {
				return errors.New("bootstrap_invite_required")
			}
			tx, e := db.Begin(ctx)
			if e != nil {
				return errors.New("bootstrap_database")
			}
			defer tx.Rollback(ctx)
			var count int
			if tx.QueryRow(ctx, `SELECT count(*) FROM users`).Scan(&count) != nil {
				return errors.New("bootstrap_database")
			}
			if count != 0 {
				return errors.New("bootstrap_requires_empty_database")
			}
			id := uuid.NewString()
			if _, e = tx.Exec(ctx, `INSERT INTO users(id,email,password_hash,display_name,admin) VALUES($1,$2,$3,'VYRN Administrator',true)`, id, email, hash); e != nil {
				return errors.New("bootstrap_database")
			}
			if _, e = tx.Exec(ctx, `INSERT INTO invites(id,code_hash,remaining,expires_at,created_by) VALUES($1,$2,100,now()+interval '7 days',$3)`, uuid.NewString(), security.Digest(invite), id); e != nil {
				return errors.New("bootstrap_database")
			}
			if e = tx.Commit(ctx); e != nil {
				return errors.New("bootstrap_database")
			}
			logger.Info("bootstrap_complete")
			return nil
		default:
			return errors.New("unknown_command")
		}
	}
	media := os.Getenv("VYRN_MEDIA_DIR")
	if media == "" {
		media = "./media"
	}
	if os.MkdirAll(media, 0700) != nil {
		return errors.New("media_directory")
	}
	reserve := uint64(10 << 30)
	if value := os.Getenv("VYRN_MIN_FREE_BYTES"); value != "" {
		reserve, err = strconv.ParseUint(value, 10, 64)
		if err != nil {
			return errors.New("invalid_reserve")
		}
	}
	var proxy *net.IPNet
	if value := os.Getenv("VYRN_TRUSTED_PROXY"); value != "" {
		_, proxy, err = net.ParseCIDR(value)
		if err != nil {
			return errors.New("invalid_proxy_network")
		}
	}
	server, err := api.New(db, api.Config{AdminDir: os.Getenv("VYRN_ADMIN_DIR"), MediaDir: media, MinFreeBytes: reserve, AdminTOTPSecret: os.Getenv("VYRN_ADMIN_TOTP_SECRET"), AllowedOrigin: os.Getenv("VYRN_ADMIN_ORIGIN"), TrustedProxy: proxy}, logger)
	if err != nil {
		return errors.New("api_initialization")
	}
	address := os.Getenv("VYRN_LISTEN")
	if address == "" {
		address = "127.0.0.1:8080"
	}
	httpServer := &http.Server{Addr: address, Handler: server.Handler(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 5 * time.Minute, IdleTimeout: 70 * time.Second, MaxHeaderBytes: 16 << 10, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	go server.RunWorkers(ctx)
	go func() {
		<-ctx.Done()
		shutdown, stop := context.WithTimeout(context.Background(), 15*time.Second)
		defer stop()
		_ = httpServer.Shutdown(shutdown)
	}()
	logger.Info("server_started", "address", address)
	if err = httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return errors.New("http_server")
	}
	return nil
}
