//go:build linux || darwin

package storage

import "golang.org/x/sys/unix"

func FreeBytes(path string) (uint64, error) {
	var s unix.Statfs_t
	err := unix.Statfs(path, &s)
	return s.Bavail * uint64(s.Bsize), err
}
