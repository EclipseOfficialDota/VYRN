package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	s.getProfile(w, r, current(r).UserID, true)
}
func (s *Server) profile(w http.ResponseWriter, r *http.Request) {
	s.getProfile(w, r, r.PathValue("id"), false)
}
func (s *Server) getProfile(w http.ResponseWriter, r *http.Request, id string, self bool) {
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var name, email, first, lastName, about string
	var avatar *string
	var last time.Time
	var admin bool
	var blocked bool
	var common int
	err := s.db.QueryRow(r.Context(), `SELECT display_name,email,avatar_id::text,last_seen,admin,COALESCE(first_name,display_name),last_name,about,
        EXISTS(SELECT 1 FROM blocks WHERE user_id=$2 AND blocked_id=users.id),
        CASE WHEN users.id=$2 THEN 0 ELSE (SELECT count(*) FROM members a JOIN members b ON b.conversation_id=a.conversation_id JOIN conversations c ON c.id=a.conversation_id WHERE a.user_id=$2 AND b.user_id=users.id AND c.kind='group') END
        FROM users WHERE id=$1 AND NOT disabled`, id, current(r).UserID).Scan(&name, &email, &avatar, &last, &admin, &first, &lastName, &about, &blocked, &common)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	out := map[string]any{"id": id, "display_name": name, "first_name": first, "last_name": lastName, "about": about, "avatar_id": avatar, "last_seen": last, "online": time.Since(last) < 45*time.Second}
	out["blocked"] = blocked
	out["common_group_count"] = common
	if self {
		out["email"] = email
		out["admin"] = admin
		out["device_id"] = current(r).DeviceID
	}
	respond(w, 200, out)
}
func (s *Server) updateProfile(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name   *string         `json:"display_name"`
		First  *string         `json:"first_name"`
		Last   *string         `json:"last_name"`
		About  *string         `json:"about"`
		Avatar json.RawMessage `json:"avatar_id"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Name == nil && in.First == nil && in.Last == nil && in.About == nil && len(in.Avatar) == 0 || in.Name != nil && (in.First != nil || in.Last != nil) {
		fail(w, 400, "invalid_request")
		return
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(r.Context())
	var name, first, last, about string
	var avatar *string
	if err = tx.QueryRow(r.Context(), `SELECT display_name,COALESCE(first_name,display_name),last_name,about,avatar_id::text FROM users WHERE id=$1 FOR UPDATE`, current(r).UserID).Scan(&name, &first, &last, &about, &avatar); err != nil {
		s.internal(w)
		return
	}
	previousName, previousFirst, previousLast, previousAbout := name, first, last, about
	previousAvatar := ""
	if avatar != nil {
		previousAvatar = *avatar
	}
	if in.Name != nil {
		first = strings.TrimSpace(*in.Name)
		last = ""
	}
	if in.First != nil {
		first = strings.TrimSpace(*in.First)
	}
	if in.Last != nil {
		last = strings.TrimSpace(*in.Last)
	}
	if in.Name != nil || in.First != nil || in.Last != nil {
		name = strings.TrimSpace(first + " " + last)
	}
	if in.About != nil {
		about = *in.About
	}
	if first == "" || utf8.RuneCountInString(first) > 100 || utf8.RuneCountInString(last) > 100 || utf8.RuneCountInString(name) > 100 || utf8.RuneCountInString(about) > 280 {
		fail(w, 400, "invalid_request")
		return
	}
	if len(in.Avatar) > 0 {
		if json.Unmarshal(in.Avatar, &avatar) != nil {
			fail(w, 400, "invalid_avatar")
			return
		}
	}
	if len(in.Avatar) > 0 && avatar != nil {
		var exists bool
		if !validID(*avatar) || tx.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM media WHERE id=$1 AND owner_id=$2 AND kind='avatar')`, *avatar, current(r).UserID).Scan(&exists) != nil || !exists {
			fail(w, 400, "invalid_avatar")
			return
		}
	}
	_, err = tx.Exec(r.Context(), `UPDATE users SET display_name=$2,avatar_id=$3,first_name=$4,last_name=$5,about=$6 WHERE id=$1`, current(r).UserID, name, avatar, first, last, about)
	if err != nil {
		s.internal(w)
		return
	}
	avatarChanged := avatar == nil && previousAvatar != "" || avatar != nil && *avatar != previousAvatar
	if (name != previousName || first != previousFirst || last != previousLast || about != previousAbout || avatarChanged) && emitProfileUpdate(r.Context(), tx, current(r).UserID) != nil {
		s.internal(w)
		return
	}
	if err = tx.Commit(r.Context()); err != nil {
		s.internal(w)
		return
	}
	s.me(w, r)
}

func emitProfileUpdate(ctx context.Context, tx pgx.Tx, user string) error {
	rows, err := tx.Query(ctx, `SELECT DISTINCT peer.user_id::text FROM members mine JOIN members peer ON peer.conversation_id=mine.conversation_id JOIN users u ON u.id=peer.user_id WHERE mine.user_id=$1 AND NOT u.disabled UNION SELECT $1::text`, user)
	if err != nil {
		return err
	}
	users, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return err
	}
	return emit(ctx, tx, users, "user.updated", map[string]string{"user_id": user})
}
func (s *Server) block(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) || id == current(r).UserID {
		fail(w, 400, "invalid_request")
		return
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(r.Context())
	tag, err := tx.Exec(r.Context(), `INSERT INTO blocks(user_id,blocked_id) SELECT $1,id FROM users WHERE id=$2 AND NOT disabled ON CONFLICT DO NOTHING`, current(r).UserID, id)
	if err != nil {
		s.internal(w)
		return
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		if err = tx.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM blocks WHERE user_id=$1 AND blocked_id=$2)`, current(r).UserID, id).Scan(&exists); err != nil {
			s.internal(w)
			return
		}
		if !exists {
			fail(w, 404, "not_found")
			return
		}
	} else if err = emit(r.Context(), tx, []string{current(r).UserID}, "block.updated", map[string]any{"user_id": id, "blocked": true}); err != nil {
		s.internal(w)
		return
	}
	if err = tx.Commit(r.Context()); err != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) unblock(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) || id == current(r).UserID {
		fail(w, 400, "invalid_request")
		return
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		s.internal(w)
		return
	}
	defer tx.Rollback(r.Context())
	tag, err := tx.Exec(r.Context(), `DELETE FROM blocks WHERE user_id=$1 AND blocked_id=$2`, current(r).UserID, id)
	if err != nil {
		s.internal(w)
		return
	}
	if tag.RowsAffected() > 0 {
		if err = emit(r.Context(), tx, []string{current(r).UserID}, "block.updated", map[string]any{"user_id": id, "blocked": false}); err != nil {
			s.internal(w)
			return
		}
	}
	if err = tx.Commit(r.Context()); err != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}

// All durable event writers take this lock before allocating sequence numbers.
// It prevents transactions committing in a different order from their cursors.
func emit(ctx context.Context, tx pgx.Tx, users []string, kind string, body any) error {
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(86142002)`); err != nil {
		return err
	}
	data, err := json.Marshal(body)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO events(user_id,kind,body) SELECT DISTINCT unnest($1::uuid[]),$2,$3::jsonb`, users, kind, data)
	return err
}
func recipients(ctx context.Context, tx pgx.Tx, conversation string) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT user_id::text FROM members WHERE conversation_id=$1 ORDER BY user_id`, conversation)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}
func memberRole(ctx context.Context, tx pgx.Tx, conversation, user string) (kind, role string, err error) {
	err = tx.QueryRow(ctx, `SELECT kind FROM conversations WHERE id=$1 FOR UPDATE`, conversation).Scan(&kind)
	if err != nil {
		return
	}
	// A joined role read before waiting on the conversation lock can retain a revoked role.
	err = tx.QueryRow(ctx, `SELECT role FROM members WHERE conversation_id=$1 AND user_id=$2`, conversation, user).Scan(&role)
	return
}

func (s *Server) conversations(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `SELECT c.id::text,c.kind,c.title,m.role,c.disappearing_seconds,c.created_at,(SELECT count(*) FROM members all_members WHERE all_members.conversation_id=c.id),
        (SELECT count(*) FROM messages msg WHERE msg.conversation_id=c.id AND msg.sender_id<>$1 AND msg.deleted_at IS NULL AND (msg.expires_at IS NULL OR msg.expires_at>now()) AND NOT EXISTS(SELECT 1 FROM receipts rc WHERE rc.message_id=msg.id AND rc.user_id=$1 AND rc.read_at IS NOT NULL))
        FROM conversations c JOIN members m ON m.conversation_id=c.id WHERE m.user_id=$1 ORDER BY c.created_at DESC LIMIT 500`, current(r).UserID)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, kind, title, role string
		var seconds, count, unread int
		var created time.Time
		if rows.Scan(&id, &kind, &title, &role, &seconds, &created, &count, &unread) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": id, "kind": kind, "title": title, "role": role, "disappearing_seconds": seconds, "created_at": created, "member_count": count, "unread_count": unread})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}
func (s *Server) conversationDetails(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var kind, title, role string
	var seconds, unread int
	var created time.Time
	var members json.RawMessage
	err := s.db.QueryRow(r.Context(), `SELECT c.kind,c.title,m.role,c.disappearing_seconds,c.created_at,
		(SELECT jsonb_agg(jsonb_build_object('id',u.id,'display_name',u.display_name,'first_name',COALESCE(u.first_name,u.display_name),'last_name',u.last_name,'role',all_members.role,'avatar_id',u.avatar_id,'joined_at',all_members.joined_at) ORDER BY all_members.joined_at,u.id)
		 FROM members all_members JOIN users u ON u.id=all_members.user_id WHERE all_members.conversation_id=c.id),
        (SELECT count(*) FROM messages msg WHERE msg.conversation_id=c.id AND msg.sender_id<>$2 AND msg.deleted_at IS NULL AND (msg.expires_at IS NULL OR msg.expires_at>now()) AND NOT EXISTS(SELECT 1 FROM receipts rc WHERE rc.message_id=msg.id AND rc.user_id=$2 AND rc.read_at IS NOT NULL))
		FROM conversations c JOIN members m ON m.conversation_id=c.id WHERE c.id=$1 AND m.user_id=$2`, id, current(r).UserID).Scan(&kind, &title, &role, &seconds, &created, &members, &unread)
	if err == pgx.ErrNoRows {
		fail(w, 404, "not_found")
		return
	}
	if err != nil {
		s.internal(w)
		return
	}
	respond(w, 200, map[string]any{"id": id, "kind": kind, "title": title, "role": role, "disappearing_seconds": seconds, "created_at": created, "members": members, "unread_count": unread})
}

func (s *Server) createConversation(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Kind    string   `json:"kind"`
		Title   string   `json:"title"`
		Members []string `json:"members"`
		Seconds int      `json:"disappearing_seconds"`
	}
	if !decode(w, r, &in) {
		return
	}
	user := current(r).UserID
	if (in.Kind != "private" && in.Kind != "group") || utf8.RuneCountInString(in.Title) > 100 || len(in.Members) > 249 || in.Seconds < 0 || in.Seconds > 31536000 || in.Kind != "group" && in.Seconds != 0 {
		fail(w, 400, "invalid_request")
		return
	}
	ids := []string{user}
	seen := map[string]bool{user: true}
	for _, id := range in.Members {
		if !validID(id) {
			fail(w, 400, "invalid_request")
			return
		}
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	if in.Kind == "private" && len(ids) != 2 || in.Kind == "group" && strings.TrimSpace(in.Title) == "" {
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
	var pair *string
	if in.Kind == "private" {
		sort.Strings(ids)
		value := strings.Join(ids, ":")
		pair = &value
		var blocked bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM blocks WHERE user_id=ANY($1::uuid[]) AND blocked_id=ANY($1::uuid[]))`, ids).Scan(&blocked); err != nil {
			s.internal(w)
			return
		}
		if blocked {
			fail(w, 403, "forbidden")
			return
		}
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM users WHERE id=ANY($1::uuid[]) AND NOT disabled`, ids).Scan(&count); err != nil {
		s.internal(w)
		return
	}
	if count != len(ids) {
		fail(w, 400, "invalid_members")
		return
	}
	id := uuid.NewString()
	var actual string
	err = tx.QueryRow(ctx, `INSERT INTO conversations(id,kind,title,private_pair,owner_id,disappearing_seconds) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(private_pair) DO UPDATE SET private_pair=excluded.private_pair RETURNING id::text`, id, in.Kind, in.Title, pair, user, in.Seconds).Scan(&actual)
	if err != nil {
		s.internal(w)
		return
	}
	if actual != id {
		if tx.Commit(ctx) != nil {
			s.internal(w)
			return
		}
		respond(w, 200, map[string]string{"id": actual})
		return
	}
	for _, member := range ids {
		role := "member"
		if member == user {
			role = "owner"
		}
		if _, err = tx.Exec(ctx, `INSERT INTO members(conversation_id,user_id,role) VALUES($1,$2,$3)`, id, member, role); err != nil {
			s.internal(w)
			return
		}
	}
	body := map[string]any{"id": id, "kind": in.Kind, "title": in.Title, "members": ids, "disappearing_seconds": in.Seconds}
	if emit(ctx, tx, ids, "conversation.created", body) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	respond(w, 201, body)
}

func (s *Server) listMembers(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		fail(w, 404, "not_found")
		return
	}
	var allowed bool
	if err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM members WHERE conversation_id=$1 AND user_id=$2)`, id, current(r).UserID).Scan(&allowed); err != nil {
		s.internal(w)
		return
	}
	if !allowed {
		fail(w, 404, "not_found")
		return
	}
	rows, err := s.db.Query(r.Context(), `SELECT u.id::text,u.display_name,m.role,u.avatar_id::text,COALESCE(u.first_name,u.display_name),u.last_name FROM members m JOIN users u ON u.id=m.user_id WHERE m.conversation_id=$1 ORDER BY m.joined_at,u.id`, id)
	if err != nil {
		s.internal(w)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var user, name, role, first, last string
		var avatar *string
		if rows.Scan(&user, &name, &role, &avatar, &first, &last) != nil {
			s.internal(w)
			return
		}
		out = append(out, map[string]any{"id": user, "display_name": name, "first_name": first, "last_name": last, "role": role, "avatar_id": avatar})
	}
	if rows.Err() != nil {
		s.internal(w)
		return
	}
	respond(w, 200, out)
}

func (s *Server) addMember(w http.ResponseWriter, r *http.Request) {
	var in struct {
		User             string `json:"user_id"`
		Role             string `json:"role"`
		PreserveExisting bool   `json:"preserve_existing"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Role == "" {
		in.Role = "member"
	}
	id := r.PathValue("id")
	if !validID(id) || !validID(in.User) || (in.Role != "member" && in.Role != "admin") {
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
	kind, role, err := memberRole(ctx, tx, id, current(r).UserID)
	if err != nil || kind != "group" || role == "member" || (in.Role == "admin" && role != "owner") {
		fail(w, 403, "forbidden")
		return
	}
	var count int
	var targetRole string
	err = tx.QueryRow(ctx, `SELECT role FROM members WHERE conversation_id=$1 AND user_id=$2`, id, in.User).Scan(&targetRole)
	if err == nil {
		if in.PreserveExisting {
			w.WriteHeader(204)
			return
		}
		if role != "owner" || targetRole == "owner" {
			fail(w, 403, "forbidden")
			return
		}
	} else if err != pgx.ErrNoRows {
		s.internal(w)
		return
	} else {
		if tx.QueryRow(ctx, `SELECT count(*) FROM members WHERE conversation_id=$1`, id).Scan(&count) != nil {
			s.internal(w)
			return
		}
		if count >= 250 {
			fail(w, 409, "group_full")
			return
		}
	}
	result, err := tx.Exec(ctx, `INSERT INTO members(conversation_id,user_id,role) SELECT $1,id,$3 FROM users WHERE id=$2 AND NOT disabled ON CONFLICT(conversation_id,user_id) DO UPDATE SET role=excluded.role`, id, in.User, in.Role)
	if err != nil {
		s.internal(w)
		return
	}
	if result.RowsAffected() == 0 {
		fail(w, 400, "invalid_member")
		return
	}
	users, err := recipients(ctx, tx, id)
	if err != nil {
		s.internal(w)
		return
	}
	if emit(ctx, tx, users, "member.updated", map[string]string{"conversation_id": id, "user_id": in.User, "role": in.Role}) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) removeMember(w http.ResponseWriter, r *http.Request) {
	id, user := r.PathValue("id"), r.PathValue("user")
	if !validID(id) || !validID(user) {
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
	kind, role, err := memberRole(ctx, tx, id, current(r).UserID)
	if err != nil || kind != "group" {
		fail(w, 403, "forbidden")
		return
	}
	var targetRole string
	if tx.QueryRow(ctx, `SELECT role FROM members WHERE conversation_id=$1 AND user_id=$2`, id, user).Scan(&targetRole) != nil {
		fail(w, 404, "not_found")
		return
	}
	if targetRole == "owner" || (user != current(r).UserID && (role == "member" || role == "admin" && targetRole == "admin")) {
		fail(w, 403, "forbidden")
		return
	}
	users, err := recipients(ctx, tx, id)
	if err != nil {
		s.internal(w)
		return
	}
	if _, err = tx.Exec(ctx, `DELETE FROM members WHERE conversation_id=$1 AND user_id=$2`, id, user); err != nil {
		s.internal(w)
		return
	}
	if emit(ctx, tx, users, "member.removed", map[string]string{"conversation_id": id, "user_id": user}) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
func (s *Server) updateConversation(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Title   *string `json:"title"`
		Seconds *int    `json:"disappearing_seconds"`
	}
	if !decode(w, r, &in) {
		return
	}
	id := r.PathValue("id")
	if !validID(id) || in.Title == nil && in.Seconds == nil || in.Title != nil && utf8.RuneCountInString(*in.Title) > 100 || in.Seconds != nil && (*in.Seconds < 0 || *in.Seconds > 31536000) {
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
	kind, role, err := memberRole(ctx, tx, id, current(r).UserID)
	if err != nil || kind == "group" && role == "member" {
		fail(w, 403, "forbidden")
		return
	}
	if kind == "group" && in.Title != nil && strings.TrimSpace(*in.Title) == "" {
		fail(w, 400, "invalid_request")
		return
	}
	var title string
	var seconds int
	if err = tx.QueryRow(ctx, `UPDATE conversations SET title=COALESCE($2,title),disappearing_seconds=COALESCE($3,disappearing_seconds) WHERE id=$1 RETURNING title,disappearing_seconds`, id, in.Title, in.Seconds).Scan(&title, &seconds); err != nil {
		s.internal(w)
		return
	}
	users, err := recipients(ctx, tx, id)
	if err != nil {
		s.internal(w)
		return
	}
	if emit(ctx, tx, users, "conversation.updated", map[string]any{"id": id, "title": title, "disappearing_seconds": seconds}) != nil || tx.Commit(ctx) != nil {
		s.internal(w)
		return
	}
	w.WriteHeader(204)
}
