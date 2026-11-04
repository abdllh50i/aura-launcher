// The floor of what a tile costs: inflate plus a full walk of the protobuf (every layer, feature, tag and geometry coordinate), in native code.
// MapLibre does this and then builds its buckets, so its real cost is above this. Build with the NDK for the unit's ABI or the emulator's.
//   tile_decode <runs> file.gz [file.gz ...]
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <zlib.h>

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

static uint64_t rv(const uint8_t **p, const uint8_t *e) {
    uint64_t v = 0;
    int s = 0;
    while (*p < e) {
        uint8_t c = *(*p)++;
        v |= (uint64_t)(c & 0x7F) << s;
        if (c < 128) break;
        s += 7;
    }
    return v;
}

static uint64_t sum, nfeat, ncoord;

static void geometry(const uint8_t *p, const uint8_t *e) {
    int32_t x = 0, y = 0;
    while (p < e) {
        uint32_t ci = (uint32_t)rv(&p, e);
        uint32_t id = ci & 7, cnt = ci >> 3;
        if (id == 7) continue;
        for (uint32_t i = 0; i < cnt && p < e; i++) {
            uint32_t a = (uint32_t)rv(&p, e), b = (uint32_t)rv(&p, e);
            x += (int32_t)((a >> 1) ^ (-(int32_t)(a & 1)));
            y += (int32_t)((b >> 1) ^ (-(int32_t)(b & 1)));
            sum += (uint32_t)(x ^ y);
            ncoord++;
        }
    }
}

static void feature(const uint8_t *p, const uint8_t *e) {
    nfeat++;
    while (p < e) {
        uint64_t k = rv(&p, e);
        int f = (int)(k >> 3), t = (int)(k & 7);
        if (t == 0) { sum += rv(&p, e); continue; }
        uint64_t n = rv(&p, e);
        if (f == 4) geometry(p, p + n);
        else if (f == 2) { const uint8_t *q = p; while (q < p + n) sum += rv(&q, p + n); }
        p += n;
    }
}

static void layer(const uint8_t *p, const uint8_t *e) {
    while (p < e) {
        uint64_t k = rv(&p, e);
        int f = (int)(k >> 3), t = (int)(k & 7);
        if (t == 0) { rv(&p, e); continue; }
        uint64_t n = rv(&p, e);
        if (f == 2) feature(p, p + n);
        else sum += n;
        p += n;
    }
}

int main(int argc, char **argv) {
    int runs = atoi(argv[1]);
    printf("sizeof(void*)=%d\n", (int)sizeof(void *));
    for (int a = 2; a < argc; a++) {
        FILE *f = fopen(argv[a], "rb");
        if (!f) { perror(argv[a]); return 1; }
        fseek(f, 0, SEEK_END);
        long gz = ftell(f);
        fseek(f, 0, SEEK_SET);
        uint8_t *in = malloc(gz), *out = malloc(8 << 20);
        fread(in, 1, gz, f);
        fclose(f);
        double inf = 0, walk = 0, worst = 0;
        uLong raw = 0;
        for (int r = 0; r < runs; r++) {
            double t0 = now_ms();
            z_stream s;
            memset(&s, 0, sizeof s);
            inflateInit2(&s, 31);
            s.next_in = in; s.avail_in = (uInt)gz; s.next_out = out; s.avail_out = 8 << 20;
            inflate(&s, Z_FINISH);
            raw = s.total_out;
            inflateEnd(&s);
            double t1 = now_ms();
            nfeat = ncoord = 0;
            const uint8_t *p = out, *e = out + raw;
            while (p < e) {
                uint64_t k = rv(&p, e);
                int t = (int)(k & 7);
                if (t == 0) { rv(&p, e); continue; }
                uint64_t n = rv(&p, e);
                if ((k >> 3) == 3) layer(p, p + n);
                p += n;
            }
            double t2 = now_ms();
            inf += t1 - t0;
            walk += t2 - t1;
            if (t2 - t0 > worst) worst = t2 - t0;
        }
        printf("%s gz=%ld raw=%lu features=%llu coords=%llu inflate=%.3f ms walk=%.3f ms worst total=%.3f ms\n", argv[a], gz, (unsigned long)raw,
               (unsigned long long)nfeat, (unsigned long long)ncoord, inf / runs, walk / runs, worst);
        free(in);
        free(out);
    }
    printf("checksum %llu\n", (unsigned long long)sum);
    return 0;
}
