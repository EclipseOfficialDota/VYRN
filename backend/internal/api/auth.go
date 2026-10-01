package api

import (
	"context"
	"errors"
	"net/http"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"xyz.eclipseofficial/vyrn/internal/security"
)

type credentials struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	Invite      string `json:"invite_code"`
	DisplayName string `json:"display_name"`
	DeviceID    string `json:"device_id"`
	DeviceName  string `json:"device_name"`
}
type tokenPair struct {
	Access       string `json:"access_token"`
	Refresh      string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
	UserID       string `json:"user_id"`
	SessionID    string `json:"session_id"`
	RecoveryCode string `json:"recovery_code,omitempty"`
}

var errDeviceLimit = errors.New("device limit")

func (s *Server) issueSession(ctx context.Context, tx pgx.Tx, user string, in credentials) (tokenPair, error) {
	var out tokenPair
	var locked string
	if err := tx.QueryRow(ctx, `SELECT id::text FROM users WHERE id=$1 FOR UPDATE`, user).Scan(&locked); err != nil {
		return out, err
	}
	_, err := tx.Exec(ctx, `UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL AND (device_id=$2 OR refresh_expires<=now())`, user, in.DeviceID)
	if err != nil {
		return out, err
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM sessions WHERE user_id=$1 AND revoked_at IS NULL`, user).Scan(&count); err != nil {
		return out, err
	}
	if count >= 3 {
		return out, errDeviceLimit
	}
	access, err := security.Token()
	if err != nil {
		return out, err
	}
	refresh, err := security.Token()
	if err != nil {
		return out, err
	}
	session := uuid.NewString()
	_, err = tx.Exec(ctx, `INSERT INTO sessions(id,user_id,device_id,device_name,access_hash,refresh_hash,access_expires,refresh_expires) VALUES($1,$2,$3,$4,$5,$6,now()+interval '15 minutes',now()+interval '30 days')`, session, user, in.DeviceID, in.DeviceName, security.Digest(access), security.Digest(refresh))
	return tokenPair{Access: access, Refresh: refresh, ExpiresIn: 900, UserID: user, SessionID: session}, err
}

func validDevice(in credentials) bool {
	return validID(in.DeviceID) && utf8.RuneCountInString(in.DeviceName) > 0 && utf8.RuneCountInString(in.DeviceName) <= 100
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var in credentials
	if !decode(w, r, &in) {
		return
	}
	email, err := security.NormalizeEmail(in.Email)
	if err != nil || !security.ValidPassword(in.Password) || !validDevice(in) || utf8.RuneCountInString(in.DisplayName) < 1 || utf8.RuneCountInString(in.DisplayName) > 100 || len(in.Invite) > 128 {
		fail(w, 400, "registration_failed")
		return
	}
	hash, err := security.HashPassword(in.Password)
	if err != nil {
		s.internal(w)
		return
	}
	recovery, err := security.Token()
	if err != nil {
		s.internal(w)
		return
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(r.Context())
	result, err := tx.Exec(r.Context(), `UPDATE invites SET remaining=remaining-1 WHERE code_hash=$1 AND remaining>0 AND expires_at>now()`, security.Digest(in.Invite))
	if err != nil || result.RowsAffected() != 1 {
		fail(w, 400, "registration_failed")
		return
	}
	user := uuid.NewString()
	_, err = tx.Exec(r.Context(), `INSERT INTO users(id,email,password_hash,display_name,recovery_hash) VALUES($1,$2,$3,$4,$5)`, user, email, hash, in.DisplayName, security.Digest(recovery))
	if err != nil {
		fail(w, 400, "registration_failed")
		return
	}
	pair, err := s.issueSession(r.Context(), tx, user, in)
	if err != nil {
		s.internal(w)
		return
	}
	if tx.Commit(r.Context()) != nil {
		s.internal(w)
		return
	}
	pair.RecoveryCode = recovery
	respond(w, 201, pair)
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var in credentials
	if !decode(w, r, &in) {
		return
	}
	email, err := security.NormalizeEmail(in.Email)
	if err != nil || !validDevice(in) || len(in.Password) > 1024 {
		security.VerifyPassword(s.dummyHash, "invalid input")
		fail(w, 401, "invalid_credentials")
		return
	}
	var user, hash string
	var disabled bool
	err = s.db.QueryRow(r.Context(), `SELECT id::text,password_hash,disabled FROM users WHERE email=$1`, email).Scan(&user, &hash, &disabled)
	if err != nil && err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	found := err == nil
	if !found {
		hash = s.dummyHash
	}
	valid := security.VerifyPassword(hash, in.Password)
	key := security.Digest(email)
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(r.Context())
	_, err = tx.Exec(r.Context(), `INSERT INTO auth_attempts(key) VALUES($1) ON CONFLICT DO NOTHING`, key)
	if err != nil {
		s.internal(w)
		return
	}
	var locked bool
	if err = tx.QueryRow(r.Context(), `SELECT locked_until>now() FROM auth_attempts WHERE key=$1 FOR UPDATE`, key).Scan(&locked); err != nil {
		s.internal(w)
		return
	}
	if !found || !valid || disabled || locked {
		_, err = tx.Exec(r.Context(), `UPDATE auth_attempts SET failures=CASE WHEN updated_at<now()-interval '15 minutes' THEN 1 ELSE failures+1 END,locked_until=CASE WHEN failures>=4 AND updated_at>=now()-interval '15 minutes' THEN now()+interval '15 minutes' ELSE locked_until END,updated_at=now() WHERE key=$1`, key)
		if err != nil || tx.Commit(r.Context()) != nil {
			s.internal(w)
			return
		}
		fail(w, 401, "invalid_credentials")
		return
	}
	if _, err = tx.Exec(r.Context(), `DELETE FROM auth_attempts WHERE key=$1`, key); err != nil {
		s.internal(w)
		return
	}
	pair, err := s.issueSession(r.Context(), tx, user, in)
	if errors.Is(err, errDeviceLimit) {
		fail(w, 409, "device_limit")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	if tx.Commit(r.Context()) != nil {
		s.internal(w)
		return
	}
	respond(w, 200, pair)
}

func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Token string `json:"refresh_token"`
	}
	if !decode(w, r, &in) {
		return
	}
	if len(in.Token) != 43 {
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
	var id, user string
	var expiry time.Time
	err = tx.QueryRow(ctx, `SELECT s.id::text,s.user_id::text,s.refresh_expires FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.refresh_hash=$1 AND s.revoked_at IS NULL AND s.refresh_expires>now() AND NOT u.disabled FOR UPDATE OF s`, security.Digest(in.Token)).Scan(&id, &user, &expiry)
	if err == pgx.ErrNoRows {
		_, err = tx.Exec(ctx, `UPDATE sessions SET revoked_at=now() WHERE id IN (SELECT session_id FROM used_refresh_tokens WHERE token_hash=$1 AND expires_at>now())`, security.Digest(in.Token))
		if err != nil || tx.Commit(ctx) != nil {
			s.internal(w)
			return
		}
		fail(w, 401, "invalid_credentials")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	access, err := security.Token()
	if err != nil {
		s.internal(w)
		return
	}
	refresh, err := security.Token()
	if err != nil {
		s.internal(w)
		return
	}
	_, err = tx.Exec(ctx, `INSERT INTO used_refresh_tokens(token_hash,session_id,expires_at) VALUES($1,$2,$3)`, security.Digest(in.Token), id, expiry)
	if err != nil {
		s.internal(w)
		return
	}
	_, err = tx.Exec(ctx, `UPDATE sessions SET access_hash=$2,refresh_hash=$3,access_expires=now()+interval '15 minutes',last_seen=now() WHERE id=$1`, id, security.Digest(access), security.Digest(refresh))
	if err != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 200, tokenPair{Access: access, Refresh: refresh, ExpiresIn: 900, UserID: user, SessionID: id})
}

func (s *Server) recoverAccount(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Email    string `json:"email"`
		Code     string `json:"recovery_code"`
		Password string `json:"new_password"`
	}
	if !decode(w, r, &in) {
		return
	}
	email, err := security.NormalizeEmail(in.Email)
	if err != nil || len(in.Code) != 43 || !security.ValidPassword(in.Password) {
		fail(w, 400, "recovery_failed")
		return
	}
	hash, err := security.HashPassword(in.Password)
	if err != nil {
		s.internal(w)
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	var id string
	err = tx.QueryRow(ctx, `UPDATE users SET password_hash=$3,recovery_hash=NULL WHERE email=$1 AND recovery_hash=$2 AND NOT disabled RETURNING id::text`, email, security.Digest(in.Code), hash).Scan(&id)
	if err == pgx.ErrNoRows {
		fail(w, 400, "recovery_failed")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	_, err = tx.Exec(ctx, `UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, id)
	if err != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]bool{"recovered": true})
}

func (s *Server) newRecoveryCode(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"password"`
	}
	if !decode(w, r, &in) {
		return
	}
	p := current(r)
	if !s.limiter.Allow("recovery:"+p.UserID, 3, time.Minute) {
		fail(w, 429, "rate_limited")
		return
	}
	select {
	case s.passwordSlots <- struct{}{}:
		defer func() { <-s.passwordSlots }()
	default:
		fail(w, 429, "busy")
		return
	}
	var hash string
	if s.db.QueryRow(r.Context(), `SELECT password_hash FROM users WHERE id=$1`, p.UserID).Scan(&hash) != nil {
		s.internal(w)
		return
	}
	if !security.VerifyPassword(hash, in.Password) {
		fail(w, 401, "invalid_credentials")
		return
	}
	code, err := security.Token()
	if err != nil {
		s.internal(w)
		return
	}
	if _, err = s.db.Exec(r.Context(), `UPDATE users SET recovery_hash=$2 WHERE id=$1`, p.UserID, security.Digest(code)); err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]string{"recovery_code": code})
}

func (s *Server) sessions(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `SELECT id::text,device_id::text,device_name,created_at,last_seen FROM sessions WHERE user_id=$1 AND revoked_at IS NULL AND refresh_expires>now() ORDER BY created_at`, current(r).UserID)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, device, name string
		var created, last time.Time
		if rows.Scan(&id, &device, &name, &created, &last) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "device_id": device, "device_name": name, "created_at": created, "last_seen": last, "current": id == current(r).SessionID})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}
func (s *Server) revokeSession(w http.ResponseWriter, r *http.Request) {
	if !validID(r.PathValue("id")) {
		fail(w, 404, "not_found")
		return
	}
	_, err := s.db.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE id=$1 AND user_id=$2`, r.PathValue("id"), current(r).UserID)
	if err != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	_, err := s.db.Exec(r.Context(), `UPDATE sessions SET revoked_at=now() WHERE id=$1`, current(r).SessionID)
	if err != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
