package api

import (
	"encoding/json"
	"net/http"
	"strconv"
)

func (s *Server) blocks(w http.ResponseWriter, r *http.Request) {
	offset, limit := 0, 20
	for key, value := range map[string]*int{"offset": &offset, "limit": &limit} {
		if raw := r.URL.Query().Get(key); raw != "" {
			parsed, err := strconv.Atoi(raw)
			if err != nil || parsed < 0 || key == "limit" && (parsed < 1 || parsed > 100) || parsed > 2147483647 {
				fail(w, 400, "invalid_request")
				return
			}
			*value = parsed
		}
	}
	var count int
	var items []byte
	err := s.db.QueryRow(r.Context(), `SELECT
		(SELECT count(*) FROM blocks WHERE user_id=$1),
		COALESCE(jsonb_agg(to_jsonb(page) ORDER BY page.created_at DESC NULLS LAST,page.id),'[]'::jsonb)
		FROM (SELECT u.id::text,u.display_name,COALESCE(u.first_name,u.display_name) AS first_name,u.last_name,u.avatar_id::text,b.created_at
		FROM blocks b JOIN users u ON u.id=b.blocked_id WHERE b.user_id=$1
		ORDER BY b.created_at DESC NULLS LAST,b.blocked_id LIMIT $2 OFFSET $3) page`, current(r).UserID, limit, offset).Scan(&count, &items)
	if err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"count": count, "items": json.RawMessage(items)})
}

func (s *Server) blockState(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 400, "invalid_request")
		return
	}
	var blocked bool
	if err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM blocks WHERE user_id=$1 AND blocked_id=$2)`, current(r).UserID, id).Scan(&blocked); err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"blocked": blocked})
}
