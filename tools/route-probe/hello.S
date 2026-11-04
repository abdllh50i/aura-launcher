// The route probe's test program (nav/route/RouteProbe.kt): does the unit's kernel run a static AArch64 executable, on a
// 32-bit userspace? Nothing but two system calls: no libc, no dynamic linker, so the firmware's libraries do not come into
// it. build.ps1 makes assets/probe/hello-aarch64; the x86_64 half is only there to try the good path on the emulator.
#if defined(__aarch64__)
    .text
    .globl _start
_start:
    mov x0, #1              // write(stdout, msg, len)
    adr x1, msg
    mov x2, #(end - msg)
    mov x8, #64
    svc #0
    mov x0, #0              // exit(0)
    mov x8, #93
    svc #0
msg:
    .ascii "amri route probe: static aarch64 ran\n"
end:
#elif defined(__x86_64__)
    .text
    .globl _start
_start:
    mov $1, %edi
    lea msg(%rip), %rsi
    mov $(end - msg), %edx
    mov $1, %eax
    syscall
    xor %edi, %edi
    mov $60, %eax
    syscall
msg:
    .ascii "amri route probe: static x86_64 ran\n"
end:
#else
#error "aarch64 or x86_64"
#endif
    .section .note.GNU-stack,"",%progbits
