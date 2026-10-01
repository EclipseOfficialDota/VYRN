package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/pquerna/otp/totp"
	"xyz.eclipseofficial/vyrn/internal/security"
)

const minute = time.Minute

func (s *Server) adminGroups(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `SELECT c.id::text,c.title,c.created_at,(SELECT count(*) FROM members m WHERE m.conversation_id=c.id) FROM conversations c WHERE c.kind='group' ORDER BY c.created_at DESC LIMIT 250`)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, title string
		var created time.Time
		var count int
		if rows.Scan(&id, &title, &created, &count) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "title": title, "created_at": created, "member_count": count})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}

func (s *Server) adminUnlock(w http.ResponseWriter, r *http.Request) {
	p := current(r)
	if !p.Admin || s.cfg.AdminTOTPSecret == "" {
		fail(w, 403, "forbidden")
		return
	}
	if !s.limiter.Allow("totp:"+p.UserID, 5, time.Minute) {
		fail(w, 429, "rate_limited")
		return
	}
	var in struct {
		Code string `json:"code"`
	}
	if !decode(w, r, &in) {
		return
	}
	now := time.Now()
	valid, err := totp.ValidateCustom(in.Code, s.cfg.AdminTOTPSecret, now, totp.ValidateOpts{Period: 30, Skew: 0, Digits: 6, Algorithm: 0})
	if err != nil || !valid {
		fail(w, 401, "invalid_credentials")
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	result, err := tx.Exec(ctx, `INSERT INTO admin_totp_used(user_id,step) VALUES($1,$2) ON CONFLICT DO NOTHING`, p.UserID, now.Unix()/30)
	if err != nil {
		s.internal(w)
		return
	}
	if result.RowsAffected() != 1 {
		fail(w, 401, "invalid_credentials")
		return
	}
	token, err := security.Token()
	if err != nil {
		s.internal(w)
		return
	}
	_, err = tx.Exec(ctx, `INSERT INTO admin_grants(token_hash,session_id,expires_at) VALUES($1,$2,now()+interval '5 minutes')`, security.Digest(token), p.SessionID)
	if err != nil || audit(tx, r, "admin.unlock", p.SessionID) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"admin_token": token, "expires_in": 300})
}
func (s *Server) requireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := current(r)
		token := r.Header.Get("X-VYRN-Admin-Token")
		if !p.Admin || len(token) != 43 {
			fail(w, 403, "forbidden")
			return
		}
		var ok bool
		err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM admin_grants WHERE token_hash=$1 AND session_id=$2 AND expires_at>now())`, security.Digest(token), p.SessionID).Scan(&ok)
		if err != nil || !ok {
			fail(w, 403, "forbidden")
			return
		}
		next.ServeHTTP(w, r)
	})
}
func audit(tx pgx.Tx, r *http.Request, action, target string) error {
	_, err := tx.Exec(r.Context(), `INSERT INTO audit_log(actor_id,action,target_id) VALUES($1,$2,$3)`, current(r).UserID, action, target)
	return err
}
func (s *Server) adminDashboard(w http.ResponseWriter, r *http.Request) {
	var users, sessions, groups, messages, bytes int64
	err := s.db.QueryRow(r.Context(), `SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM sessions WHERE revoked_at IS NULL AND refresh_expires>now()),(SELECT count(*) FROM conversations WHERE kind='group'),(SELECT count(*) FROM messages WHERE deleted_at IS NULL),(SELECT COALESCE(sum(size),0) FROM media)`).Scan(&users, &sessions, &groups, &messages, &bytes)
	if err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"users": users, "sessions": sessions, "groups": groups, "messages": messages, "storage_bytes": bytes, "requests": s.requests.Load(), "voice_calls": false, "video_calls": false})
}
func (s *Server) adminUsers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `SELECT id::text,email,display_name,disabled,admin,created_at FROM users ORDER BY created_at DESC LIMIT 250`)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, email, name string
		var disabled, admin bool
		var created time.Time
		if rows.Scan(&id, &email, &name, &disabled, &admin, &created) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "email": email, "display_name": name, "disabled": disabled, "admin": admin, "created_at": created})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}
func (s *Server) adminMutation(w http.ResponseWriter, r *http.Request, action string, fn func(pgx.Tx, string) error) {
	id := r.PathValue("id")
	if !validID(id) || id == current(r).UserID {
		fail(w, 400, "invalid_target")
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	var locked string
	if tx.QueryRow(ctx, `SELECT id::text FROM users WHERE id=$1 FOR UPDATE`, id).Scan(&locked) != nil {
		fail(w, 404, "not_found")
		return
	}
	if fn(tx, id) != nil || audit(tx, r, action, id) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) adminUpdateUser(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Disabled bool `json:"disabled"`
	}
	if !decode(w, r, &in) {
		return
	}
	s.adminMutation(w, r, "user.disabled", func(tx pgx.Tx, id string) error {
		_, err := tx.Exec(r.Context(), `UPDATE users SET disabled=$2 WHERE id=$1`, id, in.Disabled)
		if err == nil && in.Disabled {
			_, err = tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, id)
		}
		return err
	})
}
func (s *Server) adminResetPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"new_password"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !security.ValidPassword(in.Password) {
		fail(w, 400, "invalid_password")
		return
	}
	select {
	case s.passwordSlots <- struct{}{}:
		defer func() { <-s.passwordSlots }()
	default:
		fail(w, 429, "busy")
		return
	}
	hash, err := security.HashPassword(in.Password)
	if err != nil {
		s.internal(w)
		return
	}
	s.adminMutation(w, r, "user.password_reset", func(tx pgx.Tx, id string) error {
		_, e := tx.Exec(r.Context(), `UPDATE users SET password_hash=$2,recovery_hash=NULL WHERE id=$1`, id, hash)
		if e == nil {
			_, e = tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, id)
		}
		return e
	})
}
func (s *Server) adminRevokeSessions(w http.ResponseWriter, r *http.Request) {
	s.adminMutation(w, r, "user.sessions_revoked", func(tx pgx.Tx, id string) error {
		_, e := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, id)
		return e
	})
}
func (s *Server) adminInvites(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `SELECT id::text,remaining,expires_at,created_at FROM invites ORDER BY created_at DESC LIMIT 250`)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id string
		var remaining int
		var expiry, created time.Time
		if rows.Scan(&id, &remaining, &expiry, &created) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "remaining": remaining, "expires_at": expiry, "created_at": created})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}
func (s *Server) createInvite(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Uses int `json:"uses"`
		Days int `json:"days"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Uses < 1 || in.Uses > 10000 || in.Days < 1 || in.Days > 365 {
		fail(w, 400, "invalid_request")
		return
	}
	code, err := security.Token()
	if err != nil {
		s.internal(w)
		return
	}
	id := uuid.NewString()
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	_, err = tx.Exec(ctx, `INSERT INTO invites(id,code_hash,remaining,expires_at,created_by) VALUES($1,$2,$3,now()+$4*interval '1 day',$5)`, id, security.Digest(code), in.Uses, in.Days, current(r).UserID)
	if err != nil || audit(tx, r, "invite.created", id) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 201, map[string]string{"id": id, "code": code})
}
func (s *Server) deleteInvite(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	_, err = tx.Exec(ctx, `UPDATE invites SET remaining=0 WHERE id=$1`, id)
	if err != nil || audit(tx, r, "invite.revoked", id) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) adminAudit(w http.ResponseWriter, r *http.Request) {
	before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
	if before == 0 {
		before = 9223372036854775807
	}
	rows, err := s.db.Query(r.Context(), `SELECT id,actor_id::text,action,target_id,created_at FROM audit_log WHERE id<$1 ORDER BY id DESC LIMIT 100`, before)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id int64
		var actor *string
		var action, target string
		var created time.Time
		if rows.Scan(&id, &actor, &action, &target, &created) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "actor_id": actor, "action": action, "target_id": target, "created_at": created})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}
