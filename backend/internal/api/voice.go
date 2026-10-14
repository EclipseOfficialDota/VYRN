package api

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"os"
)

var errVoice = errors.New("invalid_ogg_opus_voice")

// Native mono Ogg/Opus envelope and sample timing (RFC7845/RFC3533).
// This validates framing and checksums, not decoded PCM. Buffers have page/packet limits.
func voiceDuration(path string) (int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 100<<20 {
		return 0, errVoice
	}
	var serial, sequence uint32
	var packets int
	var samples, lastGranule uint64
	var preSkip uint16
	var pending []byte
	ended := false
	for {
		header := make([]byte, 27)
		_, err = io.ReadFull(f, header)
		if err == io.EOF && ended {
			break
		}
		if err != nil || ended || string(header[:4]) != "OggS" || header[4] != 0 || header[5]&^byte(7) != 0 {
			return 0, errVoice
		}
		flags := header[5]
		pageSerial, pageSequence := binary.LittleEndian.Uint32(header[14:18]), binary.LittleEndian.Uint32(header[18:22])
		if sequence == 0 {
			serial = pageSerial
		}
		if pageSerial != serial || pageSequence != sequence || (sequence == 0) != (flags&2 != 0) || (len(pending) > 0) != (flags&1 != 0) {
			return 0, errVoice
		}
		sequence++
		laces := make([]byte, int(header[26]))
		if _, err = io.ReadFull(f, laces); err != nil || len(laces) == 0 {
			return 0, errVoice
		}
		size := 0
		for _, n := range laces {
			size += int(n)
		}
		body := make([]byte, size)
		if _, err = io.ReadFull(f, body); err != nil {
			return 0, errVoice
		}
		checksum := binary.LittleEndian.Uint32(header[22:26])
		clear(header[22:26])
		if oggCRC(header, laces, body) != checksum {
			return 0, errVoice
		}
		granule := binary.LittleEndian.Uint64(header[6:14])
		completed, audio := false, false
		offset := 0
		for i, n := range laces {
			if len(pending)+int(n) > 1<<20 {
				return 0, errVoice
			}
			pending = append(pending, body[offset:offset+int(n)]...)
			offset += int(n)
			if n == 255 {
				continue
			}
			completed = true
			switch packets {
			case 0:
				if sequence != 1 || len(pending) != 19 || !bytes.Equal(pending[:8], []byte("OpusHead")) || pending[8] != 1 || pending[9] != 1 || pending[18] != 0 || len(laces) != 1 || flags != 2 || granule != 0 {
					return 0, errVoice
				}
				preSkip = binary.LittleEndian.Uint16(pending[10:12])
			case 1:
				if !validOpusTags(pending) || i != len(laces)-1 || granule != 0 {
					return 0, errVoice
				}
			default:
				duration, e := opusPacketSamples(pending)
				if e != nil {
					return 0, e
				}
				samples += duration
				audio = true
			}
			packets++
			pending = pending[:0]
		}
		if !completed && granule != ^uint64(0) {
			return 0, errVoice
		}
		if audio {
			if granule < lastGranule || granule > samples || (flags&4 == 0 && granule != samples) {
				return 0, errVoice
			}
			lastGranule = granule
		}
		if flags&4 != 0 {
			if !audio || len(pending) != 0 || granule <= uint64(preSkip) {
				return 0, errVoice
			}
			ended = true
		}
	}
	if packets < 3 || lastGranule <= uint64(preSkip) {
		return 0, errVoice
	}
	return int64((lastGranule-uint64(preSkip))*1000+47999) / 48000, nil
}

func validOpusTags(packet []byte) bool {
	if len(packet) < 16 || !bytes.Equal(packet[:8], []byte("OpusTags")) {
		return false
	}
	left := packet[8:]
	readString := func() bool {
		if len(left) < 4 {
			return false
		}
		n := uint64(binary.LittleEndian.Uint32(left))
		left = left[4:]
		if n > uint64(len(left)) {
			return false
		}
		left = left[n:]
		return true
	}
	if !readString() || len(left) < 4 {
		return false
	}
	count := binary.LittleEndian.Uint32(left)
	left = left[4:]
	if uint64(count) > uint64(len(left)/4) {
		return false
	}
	for i := uint32(0); i < count; i++ {
		if !readString() {
			return false
		}
	}
	return true // RFC7845 permits padding after comments.
}

func opusPacketSamples(packet []byte) (uint64, error) {
	if len(packet) == 0 || len(packet) > 65536 {
		return 0, errVoice
	}
	config := packet[0] >> 3
	samples := uint64(480)
	if config >= 16 {
		samples = 120 << (config & 3)
	} else if config >= 12 {
		samples = 480 << (config & 1)
	} else if config&3 == 3 {
		samples = 2880
	} else {
		samples = 480 << (config & 3)
	}
	count := uint64(1)
	data := packet[1:]
	vbr := false
	switch packet[0] & 3 {
	case 1:
		count = 2
	case 2:
		count = 2
		vbr = true
	case 3:
		if len(data) < 1 {
			return 0, errVoice
		}
		control := data[0]
		count = uint64(control & 63)
		vbr = control&128 != 0
		data = data[1:]
		if control&64 != 0 {
			padding := 0
			for {
				if len(data) == 0 {
					return 0, errVoice
				}
				n := int(data[0])
				data = data[1:]
				if n == 255 {
					padding += 254
				} else {
					padding += n
					break
				}
			}
			if padding > len(data) {
				return 0, errVoice
			}
			data = data[:len(data)-padding]
		}
	}
	if count == 0 || count > 48 || count*samples > 5760 {
		return 0, errVoice
	}
	if vbr {
		used := 0
		for i := uint64(1); i < count; i++ {
			if len(data) == 0 {
				return 0, errVoice
			}
			n := int(data[0])
			data = data[1:]
			if n >= 252 {
				if len(data) == 0 {
					return 0, errVoice
				}
				n += 4 * int(data[0])
				data = data[1:]
			}
			used += n
		}
		if used > len(data) || len(data)-used > 1275 {
			return 0, errVoice
		}
	} else if len(data)%int(count) != 0 || len(data)/int(count) > 1275 {
		return 0, errVoice
	}
	return count * samples, nil
}

// Ogg uses a non-reflected CRC with polynomial0x04c11db7 and zero initial value.
var oggCRCTable = func() (table [256]uint32) {
	for i := range table {
		crc := uint32(i) << 24
		for bit := 0; bit < 8; bit++ {
			if crc&0x80000000 != 0 {
				crc = (crc << 1) ^ 0x04c11db7
			} else {
				crc <<= 1
			}
		}
		table[i] = crc
	}
	return
}()

func oggCRC(chunks ...[]byte) uint32 {
	var crc uint32
	for _, chunk := range chunks {
		for _, b := range chunk {
			crc = (crc << 8) ^ oggCRCTable[byte(crc>>24)^b]
		}
	}
	return crc
}
