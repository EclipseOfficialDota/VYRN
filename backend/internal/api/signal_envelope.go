package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"

	"github.com/jackc/pgx/v5"
)

type signalSender struct {
	User    string `json:"user_id"`
	Device  string `json:"device_id"`
	Address string `json:"signal_address_id"`
	Version int    `json:"version"`
}
type signalCiphertext struct {
	User       string `json:"user_id"`
	Device     string `json:"device_id"`
	Version    int    `json:"version"`
	Type       int    `json:"type"`
	Ciphertext string `json:"ciphertext"`
}
type signalEnvelope struct {
	Protocol string                      `json:"protocol"`
	Sender   signalSender                `json:"sender"`
	Devices  map[string]signalCiphertext `json:"devices"`
}

// Validate transport shape only; the receiving libsignal client authenticates ciphertext.
func parseSignalEnvelope(payload []byte) (signalEnvelope, bool) {
	var envelope signalEnvelope
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&envelope) != nil || decoder.Decode(new(any)) != io.EOF || envelope.Protocol != "signal-v2" || len(envelope.Devices) < 1 || len(envelope.Devices) > 5 || !validID(envelope.Sender.User) || !validID(envelope.Sender.Device) || !validID(envelope.Sender.Address) || envelope.Sender.Version < 1 {
		return envelope, false
	}
	for address, cipher := range envelope.Devices {
		if !validID(address) || !validID(cipher.User) || !validID(cipher.Device) || cipher.Version < 1 || cipher.Type != 2 && cipher.Type != 3 || len(cipher.Ciphertext) > 128<<10 {
			return envelope, false
		}
		decoded, err := base64.StdEncoding.Strict().DecodeString(cipher.Ciphertext)
		if err != nil || len(decoded) < 32 || base64.StdEncoding.EncodeToString(decoded) != cipher.Ciphertext {
			return envelope, false
		}
	}
	return envelope, true
}

type activeSignalDevice struct{ User, Device string }

// User locks exclude new sessions; session locks exclude revocation until message commit.
// Keep order consistent with issueSession (user before session) and key publication.
func (s *Server) lockPrivateDevices(w http.ResponseWriter, r *http.Request, tx pgx.Tx, conversation string) ([]activeSignalDevice, bool) {
	ctx := r.Context()
	p := current(r)
	rows, err := tx.Query(ctx, `SELECT u.id::text,u.disabled FROM users u JOIN members m ON m.user_id=u.id WHERE m.conversation_id=$1 ORDER BY u.id FOR SHARE OF u`, conversation)
	if err != nil {
		s.internal(w)
		return nil, false
	}
	users := []string{}
	disabled := false
	ownDisabled := false
	for rows.Next() {
		var user string
		var off bool
		if rows.Scan(&user, &off) != nil {
			rows.Close()
			s.internal(w)
			return nil, false
		}
		users = append(users, user)
		disabled = disabled || off
		ownDisabled = ownDisabled || off && user == p.UserID
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		s.internal(w)
		return nil, false
	}
	if ownDisabled {
		fail(w, 401, "unauthorized")
		return nil, false
	}
	if len(users) != 2 || disabled {
		fail(w, 404, "not_found")
		return nil, false
	}
	rows, err = tx.Query(ctx, `SELECT s.id::text,s.user_id::text,s.device_id::text,s.access_expires>clock_timestamp() FROM sessions s WHERE s.user_id=ANY($1::uuid[]) AND s.revoked_at IS NULL AND s.refresh_expires>clock_timestamp() ORDER BY s.user_id,s.device_id,s.id FOR SHARE OF s`, users)
	if err != nil {
		s.internal(w)
		return nil, false
	}
	devices := []activeSignalDevice{}
	seen := map[activeSignalDevice]bool{}
	senderLive := false
	for rows.Next() {
		var session string
		var device activeSignalDevice
		var accessLive bool
		if rows.Scan(&session, &device.User, &device.Device, &accessLive) != nil {
			rows.Close()
			s.internal(w)
			return nil, false
		}
		senderLive = senderLive || session == p.SessionID && device.User == p.UserID && device.Device == p.DeviceID && accessLive
		if !seen[device] {
			seen[device] = true
			devices = append(devices, device)
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		s.internal(w)
		return nil, false
	}
	if !senderLive {
		fail(w, 401, "unauthorized")
		return nil, false
	}
	var blocked bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM blocks WHERE user_id=ANY($1::uuid[]) AND blocked_id=ANY($1::uuid[]))`, users).Scan(&blocked); err != nil {
		s.internal(w)
		return nil, false
	}
	if blocked {
		fail(w, 403, "forbidden")
		return nil, false
	}
	return devices, true
}

func (s *Server) requireSignalFanout(w http.ResponseWriter, r *http.Request, tx pgx.Tx, devices []activeSignalDevice, payload []byte) bool {
	envelope, valid := parseSignalEnvelope(payload)
	p := current(r)
	if !valid {
		fail(w, 400, "invalid_message")
		return false
	}
	if envelope.Sender.User != p.UserID || envelope.Sender.Device != p.DeviceID {
		fail(w, 409, "device_keys_changed")
		return false
	}
	peerLive := false
	for _, device := range devices {
		peerLive = peerLive || device.User != p.UserID
	}
	if !peerLive {
		fail(w, 409, "device_keys_not_ready")
		return false
	}
	targets := 0
	for _, device := range devices {
		var address string
		var version int
		var ready bool
		err := tx.QueryRow(r.Context(), `SELECT signal_address_id::text,version,kyber_prekey IS NOT NULL FROM device_keys WHERE user_id=$1 AND device_id=$2 FOR SHARE`, device.User, device.Device).Scan(&address, &version, &ready)
		if err == pgx.ErrNoRows || err == nil && !ready {
			fail(w, 409, "device_keys_not_ready")
			return false
		}
		if err != nil {
			s.internal(w)
			return false
		}
		if device.User == p.UserID && device.Device == p.DeviceID {
			if envelope.Sender.Address != address || envelope.Sender.Version != version {
				fail(w, 409, "device_keys_changed")
				return false
			}
			continue
		}
		targets++
		cipher, ok := envelope.Devices[address]
		if !ok || cipher.User != device.User || cipher.Device != device.Device || cipher.Version != version {
			fail(w, 409, "device_keys_changed")
			return false
		}
	}
	if targets != len(envelope.Devices) {
		fail(w, 409, "device_keys_changed")
		return false
	}
	return true
}
