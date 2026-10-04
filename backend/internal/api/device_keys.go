package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"xyz.eclipseofficial/vyrn/internal/security"
)

type publicPreKey struct {
	ID        int    `json:"id"`
	PublicKey string `json:"public_key"`
	Signature string `json:"signature,omitempty"`
}
type keyPublication struct {
	Version        *int           `json:"expected_version"`
	RegistrationID int            `json:"registration_id"`
	IdentityKey    string         `json:"identity_key"`
	SignedPreKey   publicPreKey   `json:"signed_prekey"`
	KyberPreKey    publicPreKey   `json:"kyber_prekey"`
	PreKeys        []publicPreKey `json:"prekeys"`
}

// Wire-shape validation only. The receiving libsignal client verifies signatures.
func validPublicKey(value string, size int, prefix byte) bool {
	b, err := base64.StdEncoding.Strict().DecodeString(value)
	return err == nil && len(b) == size && b[0] == prefix && base64.StdEncoding.EncodeToString(b) == value
}
func validPreKey(k publicPreKey, signed, kyber bool) bool {
	if k.ID < 0 || k.ID > 16777215 {
		return false
	}
	size, prefix := 33, byte(5)
	if kyber {
		size, prefix = 1569, 8
	}
	if !validPublicKey(k.PublicKey, size, prefix) {
		return false
	}
	if !signed {
		return k.Signature == ""
	}
	sig, err := base64.StdEncoding.Strict().DecodeString(k.Signature)
	return err == nil && len(sig) == 64 && base64.StdEncoding.EncodeToString(sig) == k.Signature
}

func lockKeySession(ctx context.Context, tx pgx.Tx, p Principal) error {
	var device string
	return tx.QueryRow(ctx, `SELECT device_id::text FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=$1 AND s.user_id=$2 AND s.device_id=$3 AND s.revoked_at IS NULL AND s.access_expires>now() AND s.refresh_expires>now() AND NOT u.disabled FOR SHARE OF s`, p.SessionID, p.UserID, p.DeviceID).Scan(&device)
}

func (s *Server) publishDeviceKeys(w http.ResponseWriter, r *http.Request) {
	var in keyPublication
	if !decode(w, r, &in) {
		return
	}
	if in.Version == nil || *in.Version < 0 || in.RegistrationID < 1 || in.RegistrationID > 16380 || !validPublicKey(in.IdentityKey, 33, 5) || !validPreKey(in.SignedPreKey, true, false) || !validPreKey(in.KyberPreKey, true, true) || len(in.PreKeys) > 100 {
		fail(w, 400, "invalid_keys")
		return
	}
	seen := map[int]bool{}
	for _, key := range in.PreKeys {
		if !validPreKey(key, false, false) || seen[key.ID] {
			fail(w, 400, "invalid_keys")
			return
		}
		seen[key.ID] = true
	}
	ctx, p := r.Context(), current(r)
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	// Also serialize the initial insertion, when there is no row to lock yet.
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,87222))`, p.UserID+":"+p.DeviceID); err != nil {
		s.internal(w)
		return
	}
	if err = lockKeySession(ctx, tx, p); err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	var address, identity string
	var registration, version int
	var oldSigned, oldKyber, previousHash []byte
	err = tx.QueryRow(ctx, `SELECT signal_address_id::text,identity_key,registration_id,version,signed_prekey,kyber_prekey,publication_hash FROM device_keys WHERE user_id=$1 AND device_id=$2 FOR UPDATE`, p.UserID, p.DeviceID).Scan(&address, &identity, &registration, &version, &oldSigned, &oldKyber, &previousHash)
	if err != nil && err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	canonical, _ := json.Marshal(in)
	hash := security.Digest(string(canonical))
	if *in.Version != version && !bytes.Equal(previousHash, hash) {
		fail(w, 409, "device_keys_changed")
		return
	}
	if version != 0 && (identity != in.IdentityKey || registration != in.RegistrationID) {
		// A new identity needs a new device UUID; never silently overwrite trust.
		fail(w, 409, "device_identity_changed")
		return
	}
	signed, _ := json.Marshal(in.SignedPreKey)
	kyber, _ := json.Marshal(in.KyberPreKey)
	var currentSigned, currentKyber publicPreKey
	_ = json.Unmarshal(oldSigned, &currentSigned)
	_ = json.Unmarshal(oldKyber, &currentKyber)
	if version > 0 && ((in.SignedPreKey.ID != currentSigned.ID && in.SignedPreKey.ID < currentSigned.ID) || (oldKyber != nil && in.KyberPreKey.ID != currentKyber.ID && in.KyberPreKey.ID < currentKyber.ID)) {
		fail(w, 409, "prekey_rotation_rollback")
		return
	}
	changed := version == 0 || currentSigned != in.SignedPreKey || currentKyber != in.KyberPreKey
	if version == 0 {
		address, version = uuid.NewString(), 1
		_, err = tx.Exec(ctx, `INSERT INTO device_keys(user_id,device_id,identity_key,signed_prekey,registration_id,version,signal_address_id,kyber_prekey,publication_hash) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, p.UserID, p.DeviceID, in.IdentityKey, signed, in.RegistrationID, version, address, kyber, hash)
	} else {
		if changed {
			version++
		}
		_, err = tx.Exec(ctx, `UPDATE device_keys SET signed_prekey=$3,kyber_prekey=$4,version=$5,publication_hash=$6,updated_at=CASE WHEN $7 THEN now() ELSE updated_at END WHERE user_id=$1 AND device_id=$2`, p.UserID, p.DeviceID, signed, kyber, version, hash, changed)
	}
	if err != nil {
		s.internal(w)
		return
	}
	records := append([]publicPreKey{in.SignedPreKey, in.KyberPreKey}, in.PreKeys...)
	for i, key := range records {
		kind := "ec"
		if i == 0 {
			kind = "signed"
		}
		if i == 1 {
			kind = "kyber"
		}
		encoded, _ := json.Marshal(key)
		digest := security.Digest(string(encoded))
		_, err = tx.Exec(ctx, `INSERT INTO device_key_history(user_id,device_id,kind,key_id,record_hash) VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING`, p.UserID, p.DeviceID, kind, key.ID, digest)
		var stored []byte
		if err == nil {
			err = tx.QueryRow(ctx, `SELECT record_hash FROM device_key_history WHERE user_id=$1 AND device_id=$2 AND kind=$3 AND key_id=$4`, p.UserID, p.DeviceID, kind, key.ID).Scan(&stored)
		}
		if err != nil {
			s.internal(w)
			return
		}
		if !bytes.Equal(stored, digest) {
			fail(w, 409, "prekey_id_reused")
			return
		}
		if kind == "ec" {
			if _, err = tx.Exec(ctx, `INSERT INTO one_time_prekeys(user_id,device_id,key_id,public_key) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING`, p.UserID, p.DeviceID, key.ID, key.PublicKey); err != nil {
				s.internal(w)
				return
			}
			var public string
			if err = tx.QueryRow(ctx, `SELECT public_key FROM one_time_prekeys WHERE user_id=$1 AND device_id=$2 AND key_id=$3`, p.UserID, p.DeviceID, key.ID).Scan(&public); err != nil {
				s.internal(w)
				return
			}
			if public != key.PublicKey {
				fail(w, 409, "prekey_id_reused")
				return
			}
		}
	}
	var available int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM one_time_prekeys WHERE user_id=$1 AND device_id=$2 AND claimed_at IS NULL`, p.UserID, p.DeviceID).Scan(&available); err != nil {
		s.internal(w)
		return
	}
	if available > 1000 {
		fail(w, 409, "prekey_limit")
		return
	}
	if changed {
		rows, e := tx.Query(ctx, `SELECT DISTINCT m.user_id::text FROM members m JOIN users u ON u.id=m.user_id WHERE NOT u.disabled AND m.conversation_id IN (SELECT a.conversation_id FROM members a JOIN conversations c ON c.id=a.conversation_id WHERE a.user_id=$1 AND c.kind='private') AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.user_id=$1 AND b.blocked_id=m.user_id) OR (b.user_id=m.user_id AND b.blocked_id=$1)) UNION SELECT $1::text`, p.UserID)
		if e != nil {
			s.internal(w)
			return
		}
		var audience []string
		for rows.Next() {
			var id string
			if rows.Scan(&id) != nil {
				rows.Close()
				s.internal(w)
				return
			}
			audience = append(audience, id)
		}
		rows.Close()
		if rows.Err() != nil || emit(ctx, tx, audience, "device.keys.updated", map[string]any{"user_id": p.UserID, "device_id": p.DeviceID, "version": version}) != nil {
			s.internal(w)
			return
		}
	}
	if err = tx.Commit(ctx); err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"device_id": p.DeviceID, "signal_address_id": address, "version": version, "prekeys_available": available})
}

func keyRecipientAllowed(ctx context.Context, tx pgx.Tx, user, recipient string) (bool, error) {
	var allowed bool
	err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=$2 AND NOT u.disabled AND ($1::uuid=$2::uuid OR (EXISTS(SELECT 1 FROM members a JOIN members b ON b.conversation_id=a.conversation_id JOIN conversations c ON c.id=a.conversation_id WHERE a.user_id=$1 AND b.user_id=$2 AND c.kind='private') AND NOT EXISTS(SELECT 1 FROM blocks WHERE (user_id=$1 AND blocked_id=$2) OR (user_id=$2 AND blocked_id=$1)))))`, user, recipient).Scan(&allowed)
	return allowed, err
}

func (s *Server) deviceKeys(w http.ResponseWriter, r *http.Request) {
	recipient := r.PathValue("id")
	if !validID(recipient) {
		fail(w, 400, "invalid_request")
		return
	}
	ctx, p := r.Context(), current(r)
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	allowed, err := keyRecipientAllowed(ctx, tx, p.UserID, recipient)
	if err != nil {
		s.internal(w)
		return
	}
	if !allowed {
		fail(w, 404, "not_found")
		return
	}
	// Include unprepared active devices: clients must not silently omit them.
	rows, err := tx.Query(ctx, `SELECT s.device_id::text,k.signal_address_id::text,k.identity_key,k.registration_id,k.version,k.signed_prekey,k.kyber_prekey FROM (SELECT DISTINCT device_id FROM sessions WHERE user_id=$1 AND revoked_at IS NULL AND refresh_expires>now()) s LEFT JOIN device_keys k ON k.user_id=$1 AND k.device_id=s.device_id ORDER BY s.device_id`, recipient)
	if err != nil {
		s.internal(w)
		return
	}
	devices := []map[string]any{}
	for rows.Next() {
		var device string
		var address, identity *string
		var registration, version *int
		var signed, kyber []byte
		if rows.Scan(&device, &address, &identity, &registration, &version, &signed, &kyber) != nil {
			rows.Close()
			s.internal(w)
			return
		}
		entry := map[string]any{"device_id": device, "ready": address != nil && kyber != nil}
		if address != nil {
			entry["signal_address_id"] = *address
			entry["identity_key"] = *identity
			entry["registration_id"] = *registration
			entry["version"] = *version
			entry["signed_prekey"] = json.RawMessage(signed)
			if kyber != nil {
				entry["kyber_prekey"] = json.RawMessage(kyber)
			}
		}
		devices = append(devices, entry)
	}
	rows.Close()
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"user_id": recipient, "devices": devices})
}

func (s *Server) claimDevicePreKey(w http.ResponseWriter, r *http.Request) {
	recipient, device := r.PathValue("id"), r.PathValue("device")
	var in struct {
		Version *int `json:"expected_version"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validID(recipient) || !validID(device) || in.Version == nil || *in.Version < 1 {
		fail(w, 400, "invalid_request")
		return
	}
	ctx, p := r.Context(), current(r)
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	if err = lockKeySession(ctx, tx, p); err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	allowed, err := keyRecipientAllowed(ctx, tx, p.UserID, recipient)
	if err != nil {
		s.internal(w)
		return
	}
	if !allowed {
		fail(w, 404, "not_found")
		return
	}
	var session string
	if err = tx.QueryRow(ctx, `SELECT id::text FROM sessions WHERE user_id=$1 AND device_id=$2 AND revoked_at IS NULL AND refresh_expires>now() ORDER BY id LIMIT 1 FOR SHARE`, recipient, device).Scan(&session); err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	} else if err != nil {
		s.internal(w)
		return
	}
	var address, identity string
	var registration, version int
	var signed, kyber []byte
	err = tx.QueryRow(ctx, `SELECT signal_address_id::text,identity_key,registration_id,version,signed_prekey,kyber_prekey FROM device_keys WHERE user_id=$1 AND device_id=$2 FOR UPDATE`, recipient, device).Scan(&address, &identity, &registration, &version, &signed, &kyber)
	if err == pgx.ErrNoRows || err == nil && kyber == nil {
		fail(w, 409, "device_keys_not_ready")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	if version != *in.Version {
		fail(w, 409, "device_keys_changed")
		return
	}
	var prekey *publicPreKey
	var key publicPreKey
	err = tx.QueryRow(ctx, `UPDATE one_time_prekeys SET claimed_at=now() WHERE user_id=$1 AND device_id=$2 AND key_id=(SELECT key_id FROM one_time_prekeys WHERE user_id=$1 AND device_id=$2 AND claimed_at IS NULL ORDER BY key_id LIMIT 1) RETURNING key_id,public_key`, recipient, device).Scan(&key.ID, &key.PublicKey)
	if err == nil {
		prekey = &key
	} else if err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	if err = tx.Commit(ctx); err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"user_id": recipient, "device_id": device, "signal_address_id": address, "registration_id": registration, "identity_key": identity, "version": version, "signed_prekey": json.RawMessage(signed), "kyber_prekey": json.RawMessage(kyber), "prekey": prekey})
}
