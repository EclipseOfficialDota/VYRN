package api

import (
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Discovery searches public display names; login identifiers never participate.
func (s *Server) searchUsers(w http.ResponseWriter, r *http.Request) {
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if utf8.RuneCountInString(query) > 100 {
		fail(w, 400, "invalid_request")
		return
	}
	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 50 {
			fail(w, 400, "invalid_request")
			return
		}
		limit = value
	}
	result := []map[string]any{}
	if utf8.RuneCountInString(query) < 2 {
		respond(w, 200, result)
		return
	}
	pattern := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(query) + "%"
	rows, err := s.db.Query(r.Context(), `SELECT u.id::text,u.display_name,u.avatar_id::text FROM users u
		WHERE NOT u.disabled AND u.id<>$1 AND u.display_name ILIKE $2
		AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.user_id=$1 AND b.blocked_id=u.id) OR (b.user_id=u.id AND b.blocked_id=$1))
		ORDER BY lower(u.display_name),u.id LIMIT $3`, current(r).UserID, pattern, limit)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var id, name string
		var avatar *string
		if rows.Scan(&id, &name, &avatar) != nil {
			s.internal(w)
			return
		}
		result = append(result, map[string]any{"id": id, "display_name": name, "avatar_id": avatar})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, result)
}
