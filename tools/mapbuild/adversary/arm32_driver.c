// Runs the ARM32 libvalhalla-wrapper.so outside Android's runtime, for a test under qemu-arm with the firmware's own bionic: a small stand-in
// for JNIEnv hands it strings and byte arrays. This proves 32-bit logic (config parsing, tar mapping, tile reading on ILP32, output) with the
// armeabi-v7a code the app will ship. It cannot prove the unit's kernel: qemu-user does not fault on unaligned LDRD/LDM.
//   arm32_driver <lib.so> <config.json> <out dir> <request.json>...
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

typedef struct { jsize len; uint8_t data[]; } Arr;

#define TRAP(n) static void trap##n(void) { fprintf(stderr, "JNI function %d not implemented\n", n); fflush(stderr); abort(); }
#define T10(a) TRAP(a##0) TRAP(a##1) TRAP(a##2) TRAP(a##3) TRAP(a##4) TRAP(a##5) TRAP(a##6) TRAP(a##7) TRAP(a##8) TRAP(a##9)
T10(1) T10(2) T10(3) T10(4) T10(5) T10(6) T10(7) T10(8) T10(9) T10(10) T10(11) T10(12) T10(13) T10(14) T10(15) T10(16) T10(17) T10(18) T10(19) T10(20) T10(21) T10(22) T10(23)
#define E(a) trap##a##0, trap##a##1, trap##a##2, trap##a##3, trap##a##4, trap##a##5, trap##a##6, trap##a##7, trap##a##8, trap##a##9
static void *TRAPS[] = {E(1), E(2), E(3), E(4), E(5), E(6), E(7), E(8), E(9), E(10), E(11), E(12), E(13), E(14), E(15), E(16), E(17), E(18), E(19), E(20), E(21), E(22), E(23)};

static int g_exception = 0;
static const char *f_utf(JNIEnv *e, jstring s, jboolean *c) { if (c) *c = 0; return (const char *)s; }
static void f_relutf(JNIEnv *e, jstring s, const char *u) {}
static jsize f_utflen(JNIEnv *e, jstring s) { return (jsize)strlen((const char *)s); }
static jstring f_newutf(JNIEnv *e, const char *u) { return (jstring)strdup(u); }
static jsize f_arrlen(JNIEnv *e, jarray a) { return ((Arr *)a)->len; }
static jbyte *f_elems(JNIEnv *e, jbyteArray a, jboolean *c) { if (c) *c = 0; return (jbyte *)((Arr *)a)->data; }
static void f_relelems(JNIEnv *e, jbyteArray a, jbyte *p, jint m) {}
static void f_region(JNIEnv *e, jbyteArray a, jsize s, jsize l, jbyte *b) { memcpy(b, ((Arr *)a)->data + s, l); }
static void f_setregion(JNIEnv *e, jbyteArray a, jsize s, jsize l, const jbyte *b) { memcpy(((Arr *)a)->data + s, b, l); }
static jbyteArray f_newbytes(JNIEnv *e, jsize l) { Arr *a = malloc(sizeof(Arr) + l + 1); a->len = l; return (jbyteArray)a; }
static jboolean f_excheck(JNIEnv *e) { return g_exception != 0; }
static jthrowable f_exocc(JNIEnv *e) { return NULL; }
static void f_exclr(JNIEnv *e) { g_exception = 0; }
static void f_exdesc(JNIEnv *e) {}
static jint f_thrownew(JNIEnv *e, jclass c, const char *m) { fprintf(stderr, "ThrowNew: %s\n", m); g_exception = 1; return 0; }
static jclass f_findclass(JNIEnv *e, const char *n) { fprintf(stderr, "FindClass %s\n", n); return (jclass)strdup(n); }
static void f_delref(JNIEnv *e, jobject o) {}
static jobject f_newref(JNIEnv *e, jobject o) { return o; }
static void f_delgref(JNIEnv *e, jobject o) {}
static jint f_ver(JNIEnv *e) { return JNI_VERSION_1_6; }

static struct JNINativeInterface g_table;
static JNIEnv g_env = &g_table;

#define SET(field, fn) g_table.field = (void *)fn
static void setup(void) {
    void **t = (void **)&g_table;
    size_t n = sizeof(g_table) / sizeof(void *);
    for (size_t i = 0; i < n && i < sizeof(TRAPS) / sizeof(void *); i++) t[i] = TRAPS[i];
    SET(GetVersion, f_ver); SET(GetStringUTFChars, f_utf); SET(ReleaseStringUTFChars, f_relutf); SET(GetStringUTFLength, f_utflen); SET(NewStringUTF, f_newutf);
    SET(GetArrayLength, f_arrlen); SET(GetByteArrayElements, f_elems); SET(ReleaseByteArrayElements, f_relelems);
    SET(GetByteArrayRegion, f_region); SET(SetByteArrayRegion, f_setregion); SET(NewByteArray, f_newbytes);
    SET(ExceptionCheck, f_excheck); SET(ExceptionOccurred, f_exocc); SET(ExceptionClear, f_exclr); SET(ExceptionDescribe, f_exdesc); SET(ThrowNew, f_thrownew);
    SET(FindClass, f_findclass); SET(DeleteLocalRef, f_delref); SET(NewGlobalRef, f_newref); SET(DeleteGlobalRef, f_delgref); SET(NewLocalRef, f_newref);
}

static double ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000.0 + t.tv_nsec / 1e6; }

static void status(const char *title) {
    FILE *f = fopen("/proc/self/status", "r");
    char l[128];
    printf("%s:", title);
    if (f) { while (fgets(l, sizeof l, f)) if (!strncmp(l, "VmHWM", 5) || !strncmp(l, "RssAnon", 7) || !strncmp(l, "VmSize", 6)) { l[strcspn(l, "\n")] = 0; printf(" %s;", l); } fclose(f); }
    printf("\n");
}

static Arr *readfile(const char *p) {
    FILE *f = fopen(p, "rb");
    if (!f) { perror(p); exit(2); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    Arr *a = malloc(sizeof(Arr) + n + 1); a->len = (jsize)n; fread(a->data, 1, n, f); fclose(f);
    return a;
}

typedef jlong (*create_t)(JNIEnv *, jobject, jstring, jobject);
typedef jbyteArray (*route_t)(JNIEnv *, jobject, jlong, jbyteArray);

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("sizeof(void*)=%d sizeof(size_t)=%d sizeof(long)=%d sizeof(off_t)=%d\n", (int)sizeof(void *), (int)sizeof(size_t), (int)sizeof(long), (int)sizeof(off_t));
    setup();
    double t0 = ms();
    void *h = dlopen(argv[1], RTLD_NOW);
    if (!h) { printf("dlopen failed: %s\n", dlerror()); return 1; }
    printf("dlopen %.0f ms\n", ms() - t0);
    create_t create = (create_t)dlsym(h, "Java_com_valhalla_valhalla_ValhallaKotlin_createActor");
    route_t route = (route_t)dlsym(h, "Java_com_valhalla_valhalla_ValhallaKotlin_route");
    route_t trace = (route_t)dlsym(h, "Java_com_valhalla_valhalla_ValhallaKotlin_traceAttributes"); // a request file named trace*.json goes here
    t0 = ms();
    jlong actor = create(&g_env, NULL, (jstring)argv[2], NULL);
    printf("createActor %.0f ms, handle %s, exception %d\n", ms() - t0, actor ? "ok" : "ZERO", g_exception);
    status("after createActor");
    if (!actor) return 3;
    for (int i = 4; i < argc; i++) {
        const char *base = strrchr(argv[i], '/'); base = base ? base + 1 : argv[i];
        Arr *req = readfile(argv[i]);
        for (int run = 1; run <= 2; run++) {
            g_exception = 0;
            t0 = ms();
            Arr *res = (Arr *)(strncmp(base, "trace", 5) ? route : trace)(&g_env, NULL, actor, (jbyteArray)req);
            double took = ms() - t0;
            printf("%s run %d: %.0f ms (under qemu), %d bytes, exception %d\n", base, run, took, res ? res->len : -1, g_exception);
            if (res && run == 2) {
                char path[512];
                snprintf(path, sizeof path, "%s/%s", argv[3], base);
                FILE *o = fopen(path, "wb");
                if (o) { fwrite(res->data, 1, res->len, o); fclose(o); }
                printf("  starts: %.120s\n", (char *)res->data);
            }
        }
        status(base);
    }
    printf("done\n");
    fflush(stdout);
    _exit(0);
}
