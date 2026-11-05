# Silent songs with real tags and covers for the music screen on the emulator, made here (nothing downloaded, no music at all, and no
# real music is to be had on this PC): gen_test_songs.py <outdir> small|big
# small: 3 albums, 10 songs of 3 minutes (the names scripts/lyrics_standin.py knows). big: 40 albums of 5 songs, 25 s each, with 600 px covers.
# Then: adb push <outdir> /sdcard/Music/, and for each file am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/...
import os
import random
import struct
import sys
import zlib

out, kind = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)


def frames(seconds):
    # MPEG-1 layer III, 32 kbit/s, 44.1 kHz, mono, all zero: silence
    one = bytes([0xFF, 0xFB, 0x10, 0xC0]) + bytes(100)
    return one * int(seconds * 44100 / 1152)


def png(w, h, a, b, seed):
    r = random.Random(seed)
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        k = y / max(1, h - 1)
        line = bytearray()
        for x in range(w):
            j = x / max(1, w - 1)
            m = (k + j) / 2
            n = r.randint(-5, 5)
            for c in range(3):
                v = int(a[c] * (1 - m) + b[c] * m + n)
                line.append(max(0, min(255, v)))
        raw += line

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(bytes(raw), 6)) + chunk(b'IEND', b'')


def text(fid, s):
    d = b'\x01' + b'\xff\xfe' + s.encode('utf-16-le')
    return fid + struct.pack('>I', len(d)) + b'\0\0' + d


def tag(title, artist, album, track, cover):
    body = text(b'TIT2', title) + text(b'TPE1', artist) + text(b'TALB', album) + text(b'TRCK', str(track))
    if cover:
        d = b'\x00image/png\x00\x03\x00' + cover
        body += b'APIC' + struct.pack('>I', len(d)) + b'\0\0' + d
    n = len(body)
    size = bytes([(n >> 21) & 127, (n >> 14) & 127, (n >> 7) & 127, n & 127])
    return b'ID3\x03\x00\x00' + size + body


if kind == 'small':
    albums = [
        ('Night Drive', 'Test Artist', (20, 40, 120), (200, 60, 90), ['Corniche Lights', 'Second Road', 'Long Way Home', 'Exit 12']),
        ('Desert Songs', 'Sample Band', (180, 120, 30), (240, 200, 90), ['Sand and Wind', 'Morning Heat', 'Last Well']),
        ('أغاني الطريق', 'فنان التجربة', (20, 120, 90), (120, 220, 170), ['على الطريق', 'ليل الخبر', 'بعيد']),
    ]
    sec = 180
else:
    albums = []
    r = random.Random(5)
    for i in range(40):
        a = tuple(r.randint(10, 240) for _ in range(3))
        b = tuple(r.randint(10, 240) for _ in range(3))
        albums.append((f'Big Album {i:02d}', f'Big Artist {i % 12:02d}', a, b, [f'Big Song {i:02d}-{t}' for t in range(5)]))
    sec = 25

n = 0
for ai, (album, artist, a, b, songs) in enumerate(albums):
    cover = png(600, 600, a, b, ai)
    for ti, title in enumerate(songs):
        with open(os.path.join(out, f'{kind}-{ai:02d}-{ti:02d}.mp3'), 'wb') as f:
            f.write(tag(title, artist, album, ti + 1, cover))
            f.write(frames(sec))
        n += 1
print(n, 'songs in', out)
