// Does this qemu fault on the unaligned accesses the unit's CPU faults on? Each line tries one instruction at a bad address and reports SIGBUS or not.
// ARMv7 and AArch32 on A53: LDR/LDRH/STR/STRH may be unaligned; LDRD/STRD, LDM/STM, VLDR/VSTR, VLDM/VSTM must be word-aligned, or the CPU raises an
// alignment fault, and a 4.9 arm64 kernel has no fixup for compat tasks: SIGBUS.
//   arm32_align
#include <setjmp.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static sigjmp_buf jb;
static volatile int got;
static void h(int s) { got = s; siglongjmp(jb, 1); }
static uint8_t buf[256] __attribute__((aligned(16)));

#define TRY(name, off, ...)                                              \
    do {                                                                 \
        volatile uint8_t *p = buf + 32 + (off);                          \
        got = 0;                                                         \
        if (!sigsetjmp(jb, 1)) { __VA_ARGS__; }                          \
        printf("%-26s at +%d: %s\n", name, (int)(off), got == SIGBUS ? "SIGBUS" : got ? "other signal" : "ok"); \
    } while (0)

int main(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = h;
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGSEGV, &sa, NULL);
    for (int i = 0; i < 256; i++) buf[i] = (uint8_t)i;
    for (int off = 0; off <= 4; off++) {
        TRY("ldr", off, { uint32_t v; __asm__ volatile("ldr %0, [%1]" : "=r"(v) : "r"(p)); });
        TRY("ldrh", off, { uint32_t v; __asm__ volatile("ldrh %0, [%1]" : "=r"(v) : "r"(p)); });
        TRY("ldrd", off, { uint32_t a, b; __asm__ volatile("ldrd r2, r3, [%2]\n mov %0, r2\n mov %1, r3" : "=r"(a), "=r"(b) : "r"(p) : "r2", "r3"); });
        TRY("strd", off, { __asm__ volatile("mov r2, #1\n mov r3, #2\n strd r2, r3, [%0]" :: "r"(p) : "r2", "r3", "memory"); });
        TRY("ldm", off, { __asm__ volatile("ldm %0, {r2, r3}" :: "r"(p) : "r2", "r3"); });
        TRY("stm", off, { __asm__ volatile("mov r2, #1\n mov r3, #2\n stm %0, {r2, r3}" :: "r"(p) : "r2", "r3", "memory"); });
        TRY("vldr d0", off, { __asm__ volatile("vldr d0, [%0]" :: "r"(p) : "d0"); });
        TRY("vldr s0", off, { __asm__ volatile("vldr s0, [%0]" :: "r"(p) : "s0"); });
        TRY("vstr d0", off, { __asm__ volatile("vstr d0, [%0]" :: "r"(p) : "d0", "memory"); });
        TRY("vld1.8 (no alignment)", off, { __asm__ volatile("vld1.8 {d0}, [%0]" :: "r"(p) : "d0"); });
        TRY("vld1.64 (no qualifier)", off, { __asm__ volatile("vld1.64 {d0}, [%0]" :: "r"(p) : "d0"); });
        TRY("ldrex", off, { uint32_t v; __asm__ volatile("ldrex %0, [%1]\n clrex" : "=r"(v) : "r"(p)); });
    }
    return 0;
}
