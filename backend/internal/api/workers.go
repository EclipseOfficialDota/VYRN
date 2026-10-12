package api

import (
	"context"
	"time"

	"xyz.eclipseofficial/vyrn/internal/storage"
)

func (s *Server) RunWorkers(ctx context.Context) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.maintenance(ctx); err != nil {
				s.log.Error("maintenance_failed", "category", "database_or_io")
			}
		}
	}
}
func (s *Server) maintenance(ctx context.Context) error {
	if err := s.expireUploads(ctx); err != nil {
		return err
	}
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id::text,conversation_id::text FROM messages WHERE expires_at<=now() AND deleted_at IS NULL LIMIT 100 FOR UPDATE SKIP LOCKED`)
	if err != nil {
		return err
	}
	type expired struct{ id, conversation string }
	items := []expired{}
	for rows.Next() {
		var item expired
		if err = rows.Scan(&item.id, &item.conversation); err != nil {
			rows.Close()
			return err
		}
		items = append(items, item)
	}
	rows.Close()
	if rows.Err() != nil {
		return rows.Err()
	}
	for _, item := range items {
		if _, err = tx.Exec(ctx, `UPDATE messages SET payload='{}',media_id=NULL,deleted_at=now(),pinned=false,revision=revision+1 WHERE id=$1`, item.id); err != nil {
			return err
		}
		users, e := recipients(ctx, tx, item.conversation)
		if e != nil {
			return e
		}
		if e = emit(ctx, tx, users, "message.expired", map[string]string{"conversation_id": item.conversation, "message_id": item.id}); e != nil {
			return e
		}
	}
	_, err = tx.Exec(ctx, `UPDATE media f SET orphaned_at=COALESCE(orphaned_at,now()) WHERE NOT EXISTS(SELECT 1 FROM messages m WHERE m.media_id=f.id AND m.deleted_at IS NULL AND (m.expires_at IS NULL OR m.expires_at>now())) AND NOT EXISTS(SELECT 1 FROM users u WHERE u.avatar_id=f.id)`)
	if err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `UPDATE media f SET orphaned_at=NULL WHERE EXISTS(SELECT 1 FROM messages m WHERE m.media_id=f.id AND m.deleted_at IS NULL AND (m.expires_at IS NULL OR m.expires_at>now())) OR EXISTS(SELECT 1 FROM users u WHERE u.avatar_id=f.id)`)
	if err != nil {
		return err
	}
	rows, err = tx.Query(ctx, `SELECT id::text,storage_key FROM media WHERE orphaned_at<now()-interval '7 days' LIMIT 100 FOR UPDATE`)
	if err != nil {
		return err
	}
	files := map[string]string{}
	for rows.Next() {
		var id, key string
		if err = rows.Scan(&id, &key); err != nil {
			rows.Close()
			return err
		}
		files[id] = key
	}
	rows.Close()
	if rows.Err() != nil {
		return rows.Err()
	}
	// Leave metadata intact until deletion succeeds, so retries remain possible after I/O failure.
	store := storage.Local{Root: s.cfg.MediaDir}
	for id, key := range files {
		if err = store.Delete(ctx, key); err != nil {
			return err
		}
		if _, err = tx.Exec(ctx, `DELETE FROM media WHERE id=$1`, id); err != nil {
			return err
		}
	}
	_, err = tx.Exec(ctx, `DELETE FROM used_refresh_tokens WHERE expires_at<now();DELETE FROM admin_grants WHERE expires_at<now();DELETE FROM admin_totp_used WHERE step<extract(epoch from now())::bigint/30-2;DELETE FROM auth_attempts WHERE updated_at<now()-interval '1 day';DELETE FROM events WHERE kind='typing' AND created_at<now()-interval '1 minute'`)
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}
