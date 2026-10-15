package api

import (
	"github.com/jackc/pgx/v5"
	"net/http"
)

// Read only the existing incoming messages through a caller-observed ordinal.
func (s *Server) readConversation(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var in struct {
		MaxID int `json:"max_id"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.MaxID < 1 {
		fail(w, 400, "invalid_read_position")
		return
	}
	ctx := r.Context()
	tx, err := s.db.Begin(ctx)
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(ctx)
	user := current(r).UserID
	var exists string
	err = tx.QueryRow(ctx, `SELECT m.user_id::text FROM members m JOIN conversations c ON c.id=m.conversation_id WHERE m.conversation_id=$1 AND m.user_id=$2 FOR SHARE OF m,c`, id, user).Scan(&exists)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	var boundary int
	if tx.QueryRow(ctx, `SELECT COALESCE(max(ordinal),0) FROM messages WHERE conversation_id=$1 AND ordinal<=$2`, id, in.MaxID).Scan(&boundary) != nil {
		s.internal(w)
		return
	}
	changed, err := tx.Exec(ctx, `INSERT INTO receipts(message_id,user_id,read_at)
 SELECT id,$2,now() FROM messages WHERE conversation_id=$1 AND ordinal<=$3 AND sender_id<>$2 AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at>now())
 ON CONFLICT(message_id,user_id) DO UPDATE SET read_at=excluded.read_at WHERE receipts.read_at IS NULL`, id, user, boundary)
	if err != nil {
		s.internal(w)
		return
	}
	if changed.RowsAffected() > 0 {
		if emit(ctx, tx, []string{user}, "conversation.read", map[string]any{"conversation_id": id, "max_id": boundary}) != nil {
			s.internal(w)
			return
		}
	}
	if tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
