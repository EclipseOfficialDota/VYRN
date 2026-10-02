package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"xyz.eclipseofficial/vyrn/internal/security"
)

type messageInput struct {
	ClientID      string          `json:"client_id"`
	Payload       json.RawMessage `json:"payload"`
	Encrypted     bool            `json:"encrypted"`
	MediaID       *string         `json:"media_id"`
	ReplyTo       *string         `json:"reply_to"`
	ForwardedFrom *string         `json:"forwarded_from"`
}

func validPayload(payload json.RawMessage, encrypted bool) bool {
	if len(payload) == 0 || len(payload) > 512<<10 {
		return false
	}
	if encrypted {
		var in struct {
			Protocol string            `json:"protocol"`
			Devices  map[string]string `json:"devices"`
		}
		d := json.NewDecoder(bytes.NewReader(payload))
		d.DisallowUnknownFields()
		if d.Decode(&in) != nil || in.Protocol != "signal-v1" || len(in.Devices) < 1 || len(in.Devices) > 6 {
			return false
		}
		for id, ciphertext := range in.Devices {
			if !validID(id) || len(ciphertext) < 32 || len(ciphertext) > 128<<10 {
				return false
			}
		}
		return true
	}
	var in struct {
		Text    string `json:"text"`
		Caption string `json:"caption,omitempty"`
	}
	d := json.NewDecoder(bytes.NewReader(payload))
	d.DisallowUnknownFields()
	return d.Decode(&in) == nil && utf8.ValidString(in.Text) && utf8.ValidString(in.Caption) && utf8.RuneCountInString(in.Text)+utf8.RuneCountInString(in.Caption) <= 16384
}

func (s *Server) sendMessage(w http.ResponseWriter, r *http.Request) {
	var in messageInput
	if !decode(w, r, &in) {
		return
	}
	conversation := r.PathValue("id")
	p := current(r)
	if !validID(conversation) || !validID(in.ClientID) || !validPayload(in.Payload, in.Encrypted) {
		fail(w, 400, "invalid_message")
		return
	}
	for _, id := range []*string{in.MediaID, in.ReplyTo, in.ForwardedFrom} {
		if id != nil && !validID(*id) {
			fail(w, 400, "invalid_reference")
			return
		}
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	kind, _, err := memberRole(ctx, tx, conversation, p.UserID)
	if err != nil {
		fail(w, 404, "not_found")
		return
	}
	if kind == "private" && !in.Encrypted {
		fail(w, 422, "e2ee_required")
		return
	}
	if kind == "group" && in.Encrypted {
		fail(w, 422, "group_e2ee_not_available")
		return
	}
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,87221))`, p.UserID+":"+in.ClientID); err != nil {
		s.internal(w)
		return
	}
	var cancelled bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM cancelled_sends WHERE sender_id=$1 AND client_id=$2)`, p.UserID, in.ClientID).Scan(&cancelled); err != nil {
		s.internal(w)
		return
	}
	if cancelled {
		fail(w, 409, "send_cancelled")
		return
	}
	if kind == "private" {
		var blocked bool
		if tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM blocks b JOIN members m ON m.user_id=b.user_id JOIN members n ON n.user_id=b.blocked_id WHERE m.conversation_id=$1 AND n.conversation_id=$1)`, conversation).Scan(&blocked) != nil {
			s.internal(w)
			return
		}
		if blocked {
			fail(w, 403, "forbidden")
			return
		}
	}
	canonical, _ := json.Marshal(in)
	hash := security.Digest(conversation + string(canonical))
	var existing string
	var previous []byte
	err = tx.QueryRow(ctx, `SELECT id::text,request_hash FROM messages WHERE sender_id=$1 AND client_id=$2`, p.UserID, in.ClientID).Scan(&existing, &previous)
	if err == nil {
		if !bytes.Equal(previous, hash) {
			fail(w, 409, "idempotency_conflict")
			return
		}
		respond(w, 200, map[string]string{"id": existing})
		return
	}
	if err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	if in.Encrypted {
		var envelope struct {
			Devices map[string]string `json:"devices"`
		}
		_ = json.Unmarshal(in.Payload, &envelope)
		rows, e := tx.Query(ctx, `SELECT DISTINCT s.device_id::text FROM sessions s JOIN members m ON m.user_id=s.user_id JOIN device_keys k ON k.user_id=s.user_id AND k.device_id=s.device_id WHERE m.conversation_id=$1 AND s.revoked_at IS NULL AND s.refresh_expires>now()`, conversation)
		if e != nil {
			s.internal(w)
			return
		}
		ids := []string{}
		for rows.Next() {
			var id string
			if rows.Scan(&id) != nil {
				rows.Close()
				s.internal(w)
				return
			}
			ids = append(ids, id)
		}
		rows.Close()
		if rows.Err() != nil {
			s.internal(w)
			return
		}
		if len(ids) < 2 || len(ids) != len(envelope.Devices) {
			fail(w, 409, "device_keys_changed")
			return
		}
		for _, id := range ids {
			if _, ok := envelope.Devices[id]; !ok {
				fail(w, 409, "device_keys_changed")
				return
			}
		}
	}
	for _, ref := range []*string{in.ReplyTo, in.ForwardedFrom} {
		if ref != nil {
			var ok bool
			if tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM messages WHERE id=$1 AND conversation_id=$2 AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at>now()))`, *ref, conversation).Scan(&ok) != nil {
				s.internal(w)
				return
			}
			if !ok {
				fail(w, 400, "invalid_reference")
				return
			}
		}
	}
	if in.MediaID != nil {
		var ok bool
		if tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM media WHERE id=$1 AND owner_id=$2 AND conversation_id=$3 AND encrypted=$4)`, *in.MediaID, p.UserID, conversation, in.Encrypted).Scan(&ok) != nil {
			s.internal(w)
			return
		}
		if !ok {
			fail(w, 400, "invalid_media")
			return
		}
	}
	id := uuid.NewString()
	_, err = tx.Exec(ctx, `INSERT INTO messages(id,client_id,conversation_id,sender_id,sender_device,payload,encrypted,media_id,reply_to,forwarded_from,request_hash,expires_at) SELECT $1,$2,id,$4,$5,$6,$7,$8,$9,$10,$11,CASE WHEN disappearing_seconds>0 THEN now()+disappearing_seconds*interval '1 second' END FROM conversations WHERE id=$3`, id, in.ClientID, conversation, p.UserID, p.DeviceID, in.Payload, in.Encrypted, in.MediaID, in.ReplyTo, in.ForwardedFrom, hash)
	if err != nil {
		s.internal(w)
		return
	}
	users, err := recipients(ctx, tx, conversation)
	if err != nil {
		s.internal(w)
		return
	}
	if emit(ctx, tx, users, "message.created", map[string]string{"conversation_id": conversation, "message_id": id}) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 201, map[string]string{"id": id})
}

func (s *Server) messages(w http.ResponseWriter, r *http.Request) {
	s.readMessages(w, r, r.PathValue("id"), "")
}

func (s *Server) getMessage(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var conversation string
	err := s.db.QueryRow(r.Context(), `SELECT msg.conversation_id::text FROM messages msg JOIN members member ON member.conversation_id=msg.conversation_id AND member.user_id=$2 WHERE msg.id=$1`, id, current(r).UserID).Scan(&conversation)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	s.readMessages(w, r, conversation, id)
}

func (s *Server) readMessages(w http.ResponseWriter, r *http.Request, conversation, single string) {
	before := r.URL.Query().Get("before")
	search := r.URL.Query().Get("q")
	if !validID(conversation) || (before != "" && !validID(before)) || utf8.RuneCountInString(search) > 100 {
		fail(w, 400, "invalid_request")
		return
	}
	limit := 50
	if n, e := strconv.Atoi(r.URL.Query().Get("limit")); e == nil && n > 0 && n <= 100 {
		limit = n
	}
	var kind string
	if s.db.QueryRow(r.Context(), `SELECT c.kind FROM conversations c JOIN members m ON m.conversation_id=c.id WHERE c.id=$1 AND m.user_id=$2`, conversation, current(r).UserID).Scan(&kind) != nil {
		fail(w, 404, "not_found")
		return
	}
	if search != "" && kind == "private" {
		fail(w, 422, "search_on_device")
		return
	}
	var cursor any
	if before != "" {
		cursor = before
	}
	var messageID any
	if single != "" {
		messageID = single
	}
	rows, err := s.db.Query(r.Context(), `SELECT msg.id::text,client_id::text,sender_id::text,sender_device::text,payload,encrypted,media_id::text,reply_to::text,forwarded_from::text,revision,pinned,created_at,edited_at,deleted_at,expires_at,ordinal,
        CASE WHEN deleted_at IS NOT NULL THEN '[]'::jsonb ELSE COALESCE((SELECT jsonb_agg(summary ORDER BY summary->>'emoji') FROM (SELECT jsonb_build_object('emoji',emoji,'count',count(*),'mine',bool_or(user_id=$5)) AS summary FROM reactions WHERE message_id=msg.id GROUP BY emoji) AS grouped),'[]'::jsonb) END,
        CASE WHEN deleted_at IS NOT NULL THEN '{"delivered":0,"read":0,"delivered_by_me":false,"read_by_me":false}'::jsonb ELSE (SELECT jsonb_build_object('delivered',count(*),'read',count(read_at),'delivered_by_me',COALESCE(bool_or(user_id=$5),false),'read_by_me',COALESCE(bool_or(user_id=$5 AND read_at IS NOT NULL),false)) FROM receipts WHERE message_id=msg.id AND user_id<>msg.sender_id) END
        FROM messages msg WHERE conversation_id=$1 AND (expires_at IS NULL OR expires_at>now() OR deleted_at IS NOT NULL)
        AND EXISTS(SELECT 1 FROM members WHERE conversation_id=msg.conversation_id AND user_id=$5)
        AND ($2::uuid IS NULL OR ordinal<(SELECT ordinal FROM messages WHERE id=$2 AND conversation_id=$1))
        AND ($6::uuid IS NULL OR msg.id=$6)
        AND ($3='' OR (NOT encrypted AND deleted_at IS NULL AND position(lower($3) in lower(payload->>'text'))>0)) ORDER BY ordinal DESC LIMIT $4`, conversation, cursor, search, limit, current(r).UserID, messageID)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, client, sender, device string
		var payload, reactions, receipts json.RawMessage
		var enc, pinned bool
		var media, reply, forward *string
		var revision, ordinal int
		var created time.Time
		var edited, deleted, expires *time.Time
		if rows.Scan(&id, &client, &sender, &device, &payload, &enc, &media, &reply, &forward, &revision, &pinned, &created, &edited, &deleted, &expires, &ordinal, &reactions, &receipts) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "ordinal": ordinal, "conversation_id": conversation, "client_id": client, "sender_id": sender, "sender_device": device, "payload": payload, "encrypted": enc, "media_id": media, "reply_to": reply, "forwarded_from": forward, "revision": revision, "pinned": pinned, "created_at": created, "edited_at": edited, "deleted_at": deleted, "expires_at": expires, "reactions": reactions, "receipts": receipts})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	if single != "" {
		if len(out) == 0 {
			fail(w, 404, "not_found")
			return
		}
		respond(w, 200, out[0])
		return
	}
	respond(w, 200, out)
}

func (s *Server) editMessage(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Payload   json.RawMessage `json:"payload"`
		Revision  int             `json:"revision"`
		Encrypted bool            `json:"encrypted"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validPayload(in.Payload, in.Encrypted) || in.Revision < 1 {
		fail(w, 400, "invalid_message")
		return
	}
	s.mutateMessage(w, r, "message.edited", func(tx pgx.Tx, id, conversation, sender, role string, enc bool) bool {
		if sender != current(r).UserID || enc != in.Encrypted {
			fail(w, 403, "forbidden")
			return false
		}
		result, err := tx.Exec(r.Context(), `UPDATE messages SET payload=$2,revision=revision+1,edited_at=now() WHERE id=$1 AND revision=$3 AND deleted_at IS NULL`, id, in.Payload, in.Revision)
		if err != nil {
			s.internal(w)
			return false
		}
		if result.RowsAffected() != 1 {
			fail(w, 409, "revision_conflict")
			return false
		}
		return true
	})
}
func (s *Server) deleteMessage(w http.ResponseWriter, r *http.Request) {
	s.mutateMessage(w, r, "message.deleted", func(tx pgx.Tx, id, conversation, sender, role string, enc bool) bool {
		if sender != current(r).UserID && role == "member" {
			fail(w, 403, "forbidden")
			return false
		}
		_, err := tx.Exec(r.Context(), `UPDATE messages SET payload='{}',media_id=NULL,deleted_at=now(),revision=revision+1,pinned=false WHERE id=$1`, id)
		if err != nil {
			s.internal(w)
			return false
		}
		return true
	})
}
func (s *Server) mutateMessage(w http.ResponseWriter, r *http.Request, event string, fn func(pgx.Tx, string, string, string, string, bool) bool) {
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
	var conversation, sender, role string
	var enc bool
	err = tx.QueryRow(ctx, `SELECT msg.conversation_id::text,msg.sender_id::text,m.role,msg.encrypted FROM messages msg JOIN members m ON m.conversation_id=msg.conversation_id WHERE msg.id=$1 AND m.user_id=$2 AND msg.deleted_at IS NULL AND (msg.expires_at IS NULL OR msg.expires_at>now()) FOR UPDATE OF msg`, id, current(r).UserID).Scan(&conversation, &sender, &role, &enc)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	if !fn(tx, id, conversation, sender, role, enc) {
		return
	}
	users, err := recipients(ctx, tx, conversation)
	if err != nil {
		s.internal(w)
		return
	}
	if emit(ctx, tx, users, event, map[string]string{"conversation_id": conversation, "message_id": id, "user_id": current(r).UserID}) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) react(w http.ResponseWriter, r *http.Request)   { s.reaction(w, r, true) }
func (s *Server) unreact(w http.ResponseWriter, r *http.Request) { s.reaction(w, r, false) }
func (s *Server) reaction(w http.ResponseWriter, r *http.Request, add bool) {
	var in struct {
		Emoji string `json:"emoji"`
	}
	if !decode(w, r, &in) {
		return
	}
	if utf8.RuneCountInString(in.Emoji) < 1 || utf8.RuneCountInString(in.Emoji) > 16 {
		fail(w, 400, "invalid_reaction")
		return
	}
	s.mutateMessage(w, r, "message.reaction", func(tx pgx.Tx, id, conversation, sender, role string, enc bool) bool {
		sql := `DELETE FROM reactions WHERE message_id=$1 AND user_id=$2 AND emoji=$3`
		if add {
			var count int
			if err := tx.QueryRow(r.Context(), `SELECT count(*) FROM reactions WHERE message_id=$1 AND user_id=$2 AND emoji<>$3`, id, current(r).UserID, in.Emoji).Scan(&count); err != nil {
				s.internal(w)
				return false
			}
			if count >= 3 {
				fail(w, 409, "reaction_limit")
				return false
			}
			sql = `INSERT INTO reactions(message_id,user_id,emoji) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`
		}
		if _, err := tx.Exec(r.Context(), sql, id, current(r).UserID, in.Emoji); err != nil {
			s.internal(w)
			return false
		}
		return true
	})
}
func (s *Server) receipt(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Read bool `json:"read"`
	}
	if !decode(w, r, &in) {
		return
	}
	s.mutateMessage(w, r, "message.receipt", func(tx pgx.Tx, id, conversation, sender, role string, enc bool) bool {
		_, err := tx.Exec(r.Context(), `INSERT INTO receipts(message_id,user_id,read_at) VALUES($1,$2,CASE WHEN $3 THEN now() END) ON CONFLICT(message_id,user_id) DO UPDATE SET read_at=COALESCE(receipts.read_at,excluded.read_at)`, id, current(r).UserID, in.Read)
		if err != nil {
			s.internal(w)
			return false
		}
		return true
	})
}
func (s *Server) pin(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Pinned bool `json:"pinned"`
	}
	if !decode(w, r, &in) {
		return
	}
	s.mutateMessage(w, r, "message.pinned", func(tx pgx.Tx, id, conversation, sender, role string, enc bool) bool {
		if role == "member" && !enc {
			fail(w, 403, "forbidden")
			return false
		}
		if _, err := tx.Exec(r.Context(), `UPDATE messages SET pinned=$2 WHERE id=$1`, id, in.Pinned); err != nil {
			s.internal(w)
			return false
		}
		return true
	})
}
func (s *Server) saveDraft(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Payload   json.RawMessage `json:"payload"`
		Encrypted bool            `json:"encrypted"`
	}
	if !decode(w, r, &in) {
		return
	}
	id := r.PathValue("id")
	if !validID(id) || !validPayload(in.Payload, in.Encrypted) {
		fail(w, 400, "invalid_request")
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	kind, _, err := memberRole(ctx, tx, id, current(r).UserID)
	if err != nil {
		fail(w, 404, "not_found")
		return
	}
	if kind == "private" && !in.Encrypted {
		fail(w, 422, "e2ee_required")
		return
	}
	_, err = tx.Exec(ctx, `INSERT INTO drafts(conversation_id,user_id,payload) VALUES($1,$2,$3) ON CONFLICT(conversation_id,user_id) DO UPDATE SET payload=excluded.payload,updated_at=now()`, id, current(r).UserID, in.Payload)
	if err != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) getDraft(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var payload json.RawMessage
	err := s.db.QueryRow(r.Context(), `SELECT d.payload FROM drafts d JOIN members m ON m.conversation_id=d.conversation_id AND m.user_id=d.user_id WHERE d.conversation_id=$1 AND d.user_id=$2`, id, current(r).UserID).Scan(&payload)
	if err == pgx.ErrNoRows {
		respond(w, 200, map[string]any{"payload": nil})
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"payload": payload})
}
func (s *Server) typing(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	p := current(r)
	if !s.limiter.Allow("typing:"+p.UserID+":"+id, 1, 3*time.Second) {
		w.WriteHeader(204)
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	if _, _, err = memberRole(ctx, tx, id, p.UserID); err != nil {
		fail(w, 404, "not_found")
		return
	}
	users, err := recipients(ctx, tx, id)
	if err != nil {
		s.internal(w)
		return
	}
	if emit(ctx, tx, users, "typing", map[string]any{"conversation_id": id, "user_id": p.UserID, "expires_at": time.Now().Add(5 * time.Second)}) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
