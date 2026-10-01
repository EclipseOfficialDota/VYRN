package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

type event struct {
	Sequence int64           `json:"sequence"`
	Kind     string          `json:"kind"`
	Body     json.RawMessage `json:"body"`
	Created  time.Time       `json:"created_at"`
}

func (s *Server) events(ctx context.Context, user string, cursor int64) ([]event, error) {
	rows, err := s.db.Query(ctx, `SELECT sequence,kind,body,created_at FROM events WHERE user_id=$1 AND sequence>$2 AND (kind<>'typing' OR created_at>now()-interval '5 seconds') ORDER BY sequence LIMIT 100`, user, cursor)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []event{}
	for rows.Next() {
		var e event
		if err = rows.Scan(&e.Sequence, &e.Kind, &e.Body, &e.Created); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
func cursorFrom(r *http.Request) (int64, error) {
	if r.URL.Query().Get("cursor") == "" {
		return 0, nil
	}
	return strconv.ParseInt(r.URL.Query().Get("cursor"), 10, 64)
}
func (s *Server) syncEvents(w http.ResponseWriter, r *http.Request) {
	cursor, err := cursorFrom(r)
	if err != nil || cursor < 0 {
		fail(w, 400, "invalid_cursor")
		return
	}
	events, err := s.events(r.Context(), current(r).UserID, cursor)
	if err != nil {
		s.internal(w)
		return
	}
	next := cursor
	if len(events) > 0 {
		next = events[len(events)-1].Sequence
	}
	respond(w, 200, map[string]any{"events": events, "cursor": next, "has_more": len(events) == 100})
}
func (s *Server) websocket(w http.ResponseWriter, r *http.Request) {
	cursor, err := cursorFrom(r)
	if err != nil || cursor < 0 {
		fail(w, 400, "invalid_cursor")
		return
	}
	p := current(r)
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer c.CloseNow()
	c.SetReadLimit(4096)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	ack := make(chan int64, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			var input struct {
				Type   string `json:"type"`
				Cursor int64  `json:"cursor"`
			}
			if wsjson.Read(ctx, c, &input) != nil {
				return
			}
			if input.Type != "ack" || input.Cursor < 0 {
				_ = c.Close(websocket.StatusPolicyViolation, "invalid acknowledgement")
				return
			}
			select {
			case ack <- input.Cursor:
			case <-ctx.Done():
				return
			}
		}
	}()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	heartbeat := time.NewTicker(20 * time.Second)
	defer heartbeat.Stop()
	sent := cursor
	acked := cursor
	waiting := false
	lastAck := time.Now()
	write := func(v any) error {
		writeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		return wsjson.Write(writeCtx, c, v)
	}
	if write(map[string]any{"type": "ready", "cursor": cursor, "heartbeat_seconds": 20}) != nil {
		return
	}
	for {
		select {
		case <-done:
			return
		case <-ctx.Done():
			return
		case n := <-ack:
			if n > sent || n < acked {
				_ = c.Close(websocket.StatusPolicyViolation, "invalid cursor")
				return
			}
			acked = n
			if n == sent {
				waiting = false
			}
			lastAck = time.Now()
			if _, err = s.db.Exec(ctx, `UPDATE sessions SET cursor=GREATEST(cursor,$2),last_seen=now() WHERE id=$1`, p.SessionID, n); err != nil {
				return
			}
		case <-heartbeat.C:
			if _, err = s.authenticate(ctx, token); err != nil {
				_ = c.Close(websocket.StatusPolicyViolation, "reauthenticate")
				return
			}
			if _, err = s.db.Exec(ctx, `UPDATE users SET last_seen=now() WHERE id=$1`, p.UserID); err != nil {
				return
			}
			pingCtx, pingCancel := context.WithTimeout(ctx, 5*time.Second)
			err = c.Ping(pingCtx)
			pingCancel()
			if err != nil {
				return
			}
		case <-ticker.C:
			if waiting {
				if time.Since(lastAck) > 45*time.Second {
					_ = c.Close(websocket.StatusPolicyViolation, "acknowledgement timeout")
					return
				}
				continue
			}
			if _, err = s.authenticate(ctx, token); err != nil {
				_ = c.Close(websocket.StatusPolicyViolation, "reauthenticate")
				return
			}
			items, e := s.events(ctx, p.UserID, sent)
			if e != nil {
				return
			}
			if len(items) == 0 {
				continue
			}
			sent = items[len(items)-1].Sequence
			if write(map[string]any{"type": "events", "events": items, "cursor": sent}) != nil {
				return
			}
			waiting = true
			lastAck = time.Now()
		}
	}
}
