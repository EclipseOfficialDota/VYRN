package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"xyz.eclipseofficial/vyrn/internal/security"
)

type Config struct {
	AdminDir        string
	MediaDir        string
	MinFreeBytes    uint64
	UploadLimit     int64
	QuotaBytes      int64
	AdminTOTPSecret string
	AllowedOrigin   string
	TrustedProxy    *net.IPNet
}

type Server struct {
	db            *pgxpool.Pool
	cfg           Config
	log           *slog.Logger
	passwordSlots chan struct{}
	uploadSlots   chan struct{}
	dummyHash     string
	limiter       *Limiter
	requests      atomic.Uint64
	storageMu     sync.Mutex
	reservedBytes uint64
}

type Principal struct {
	UserID    string `json:"user_id"`
	SessionID string `json:"session_id"`
	DeviceID  string `json:"device_id"`
	Admin     bool   `json:"admin"`
}
type contextKey struct{}

func New(db *pgxpool.Pool, cfg Config, logger *slog.Logger) (*Server, error) {
	dummy, err := security.HashPassword("constant timing placeholder, never a user password")
	if err != nil {
		return nil, err
	}
	if cfg.UploadLimit == 0 {
		cfg.UploadLimit = 250 << 20
	}
	if cfg.QuotaBytes == 0 {
		cfg.QuotaBytes = 2 << 30
	}
	return &Server{db: db, cfg: cfg, log: logger, passwordSlots: make(chan struct{}, 2), uploadSlots: make(chan struct{}, 2), dummyHash: dummy, limiter: NewLimiter()}, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	if s.cfg.AdminDir != "" {
		mux.Handle("/", http.FileServer(http.Dir(s.cfg.AdminDir)))
	}
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) { respond(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if s.db.Ping(ctx) != nil {
			fail(w, 503, "not_ready")
			return
		}
		respond(w, 200, map[string]string{"status": "ready"})
	})
	mux.Handle("POST /v1/auth/register", s.authThrottle(http.HandlerFunc(s.register)))
	mux.Handle("POST /v1/auth/login", s.authThrottle(http.HandlerFunc(s.login)))
	mux.Handle("POST /v1/auth/refresh", s.authThrottle(http.HandlerFunc(s.refresh)))
	mux.Handle("POST /v1/auth/recover", s.authThrottle(http.HandlerFunc(s.recoverAccount)))
	secure := func(pattern string, fn http.HandlerFunc) { mux.Handle(pattern, s.requireAuth(fn)) }
	secure("GET /v1/me", s.me)
	secure("PATCH /v1/me", s.updateProfile)
	secure("GET /v1/sessions", s.sessions)
	secure("DELETE /v1/sessions/{id}", s.revokeSession)
	secure("POST /v1/auth/logout", s.logout)
	secure("POST /v1/auth/recovery-code", s.newRecoveryCode)
	secure("GET /v1/users/{id}", s.profile)
	secure("PUT /v1/blocks/{id}", s.block)
	secure("DELETE /v1/blocks/{id}", s.unblock)
	secure("GET /v1/conversations", s.conversations)
	secure("POST /v1/conversations", s.createConversation)
	secure("POST /v1/conversations/{id}/members", s.addMember)
	secure("GET /v1/conversations/{id}/members", s.listMembers)
	secure("DELETE /v1/conversations/{id}/members/{user}", s.removeMember)
	secure("PATCH /v1/conversations/{id}", s.updateConversation)
	secure("GET /v1/conversations/{id}/messages", s.messages)
	secure("POST /v1/conversations/{id}/messages", s.sendMessage)
	secure("PUT /v1/conversations/{id}/sends/{client}/cancel", s.cancelSend)
	secure("GET /v1/messages/{id}", s.getMessage)
	secure("PATCH /v1/messages/{id}", s.editMessage)
	secure("DELETE /v1/messages/{id}", s.deleteMessage)
	secure("PUT /v1/messages/{id}/reactions", s.react)
	secure("DELETE /v1/messages/{id}/reactions", s.unreact)
	secure("PUT /v1/messages/{id}/receipt", s.receipt)
	secure("PUT /v1/messages/{id}/pin", s.pin)
	secure("PUT /v1/conversations/{id}/draft", s.saveDraft)
	secure("GET /v1/conversations/{id}/draft", s.getDraft)
	secure("POST /v1/conversations/{id}/typing", s.typing)
	secure("GET /v1/sync", s.syncEvents)
	secure("GET /v1/ws", s.websocket)
	secure("POST /v1/media", s.upload)
	secure("GET /v1/media/{id}", s.download)
	secure("POST /v1/admin/unlock", s.adminUnlock)
	admin := func(pattern string, fn http.HandlerFunc) { mux.Handle(pattern, s.requireAuth(s.requireAdmin(fn))) }
	admin("GET /v1/admin/dashboard", s.adminDashboard)
	admin("GET /v1/admin/users", s.adminUsers)
	admin("GET /v1/admin/groups", s.adminGroups)
	admin("PATCH /v1/admin/users/{id}", s.adminUpdateUser)
	admin("POST /v1/admin/users/{id}/reset-password", s.adminResetPassword)
	admin("POST /v1/admin/users/{id}/revoke-sessions", s.adminRevokeSessions)
	admin("GET /v1/admin/invites", s.adminInvites)
	admin("POST /v1/admin/invites", s.createInvite)
	admin("DELETE /v1/admin/invites/{id}", s.deleteInvite)
	admin("GET /v1/admin/audit", s.adminAudit)
	return s.common(mux)
}

func respond(w http.ResponseWriter, code int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(value)
}
func fail(w http.ResponseWriter, code int, kind string) {
	respond(w, code, map[string]string{"error": kind})
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		fail(w, 400, "invalid_request")
		return false
	}
	if err := d.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		fail(w, 400, "invalid_request")
		return false
	}
	return true
}
func current(r *http.Request) Principal { return r.Context().Value(contextKey{}).(Principal) }
func validID(id string) bool            { _, err := uuid.Parse(id); return err == nil }
func (s *Server) internal(w http.ResponseWriter) {
	s.log.Error("request_failed", "category", "database_or_io")
	fail(w, 500, "internal_error")
}

func (s *Server) authenticate(ctx context.Context, token string) (Principal, error) {
	var p Principal
	if len(token) != 43 {
		return p, errors.New("unauthorized")
	}
	err := s.db.QueryRow(ctx, `SELECT u.id::text,s.id::text,s.device_id::text,u.admin FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.access_hash=$1 AND s.revoked_at IS NULL AND s.access_expires>now() AND s.refresh_expires>now() AND NOT u.disabled`, security.Digest(token)).Scan(&p.UserID, &p.SessionID, &p.DeviceID, &p.Admin)
	return p, err
}
func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			fail(w, 401, "unauthorized")
			return
		}
		p, err := s.authenticate(r.Context(), strings.TrimPrefix(h, "Bearer "))
		if err != nil {
			fail(w, 401, "unauthorized")
			return
		}
		if !s.limiter.Allow("user:"+p.UserID, 300, time.Minute) {
			w.Header().Set("Retry-After", "60")
			fail(w, 429, "rate_limited")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), contextKey{}, p)))
	})
}

func (s *Server) common(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.requests.Add(1)
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		if origin := r.Header.Get("Origin"); origin != "" {
			if origin != s.cfg.AllowedOrigin {
				fail(w, 403, "origin_denied")
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization,Content-Type,X-VYRN-Admin-Token")
			w.Header().Set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS")
			if r.Method == "OPTIONS" {
				w.WriteHeader(204)
				return
			}
		}
		defer func() {
			if recover() != nil {
				s.log.Error("request_panic")
				fail(w, 500, "internal_error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) authThrottle(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		if s.cfg.TrustedProxy != nil && s.cfg.TrustedProxy.Contains(net.ParseIP(ip)) {
			if forwarded := net.ParseIP(r.Header.Get("X-Real-IP")); forwarded != nil {
				ip = forwarded.String()
			}
		}
		if !s.limiter.Allow("auth:"+ip, 20, time.Minute) {
			w.Header().Set("Retry-After", "60")
			fail(w, 429, "rate_limited")
			return
		}
		select {
		case s.passwordSlots <- struct{}{}:
			defer func() { <-s.passwordSlots }()
			next.ServeHTTP(w, r)
		default:
			w.Header().Set("Retry-After", "2")
			fail(w, 429, "busy")
		}
	})
}

type bucket struct {
	count int
	until time.Time
}
type Limiter struct {
	mu      sync.Mutex
	buckets map[string]bucket
}

func NewLimiter() *Limiter { return &Limiter{buckets: map[string]bucket{}} }
func (l *Limiter) Allow(key string, limit int, window time.Duration) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if len(l.buckets) > 10000 {
		for key, b := range l.buckets {
			if now.After(b.until) {
				delete(l.buckets, key)
			}
		}
	}
	b, exists := l.buckets[key]
	if !exists && len(l.buckets) > 20000 {
		return false
	}
	if now.After(b.until) {
		b = bucket{until: now.Add(window)}
	}
	if b.count >= limit {
		return false
	}
	b.count++
	l.buckets[key] = b
	return true
}
