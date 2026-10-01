package storage

import (
	"context"
	"io"
	"os"
	"path/filepath"

	"github.com/google/uuid"
)

// Store deliberately exposes streams, not public URLs. S3 adapters must preserve authorization in the API.
type Store interface {
	Open(context.Context, string) (io.ReadCloser, error)
	Delete(context.Context, string) error
}
type Local struct{ Root string }

func (l Local) Path(key string) (string, error) {
	if _, err := uuid.Parse(key); err != nil {
		return "", os.ErrInvalid
	}
	return filepath.Join(l.Root, key), nil
}
func (l Local) Open(_ context.Context, key string) (io.ReadCloser, error) {
	p, e := l.Path(key)
	if e != nil {
		return nil, e
	}
	return os.Open(p)
}
func (l Local) Delete(_ context.Context, key string) error {
	p, e := l.Path(key)
	if e != nil {
		return e
	}
	e = os.Remove(p)
	if os.IsNotExist(e) {
		return nil
	}
	return e
}
