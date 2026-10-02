package api

import (
	"net/http"

	"github.com/jackc/pgx/v5"
)

// cancelSend arbitrates cancellation against every send with the same user/client ID.
// A committed send is returned, never silently deleted. An absent send is permanently
// fenced so delayed offline retries cannot create a message after cancellation succeeds.
func (s *Server) cancelSend(w http.ResponseWriter, r *http.Request) {
	conversation, client := r.PathValue("id"), r.PathValue("client")
	if !validID(conversation) || !validID(client) {
		fail(w, 400, "invalid_request")
		return
	}
	ctx, user := r.Context(), current(r).UserID
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	if _, _, err = memberRole(ctx, tx, conversation, user); err != nil {
		fail(w, 404, "not_found")
		return
	}
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,87221))`, user+":"+client); err != nil {
		s.internal(w)
		return
	}
	var id, existingConversation string
	err = tx.QueryRow(ctx, `SELECT id::text,conversation_id::text FROM messages WHERE sender_id=$1 AND client_id=$2`, user, client).Scan(&id, &existingConversation)
	if err == nil {
		if existingConversation != conversation {
			fail(w, 409, "idempotency_conflict")
			return
		}
		respond(w, 200, map[string]string{"status": "committed", "message_id": id})
		return
	}
	if err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	err = tx.QueryRow(ctx, `SELECT conversation_id::text FROM cancelled_sends WHERE sender_id=$1 AND client_id=$2`, user, client).Scan(&existingConversation)
	if err == nil && existingConversation != conversation {
		fail(w, 409, "idempotency_conflict")
		return
	}
	if err != nil && err != pgx.ErrNoRows {
		s.internal(w)
		return
	}
	if _, err = tx.Exec(ctx, `INSERT INTO cancelled_sends(sender_id,client_id,conversation_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`, user, client, conversation); err != nil {
		s.internal(w)
		return
	}
	if tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]string{"status": "cancelled"})
}
