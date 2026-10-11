/* K2501 AIC8800 GOC: replace AT#VI's cached status with AVRCP GetPlayStatus.
 * Addresses are for the SHA-256 pinned stock gocsdk_8800 only. All references
 * are PC relative, including the original daemon's globals and Thumb calls.
 */
.syntax unified
.thumb
.section .hook,"ax",%progbits
.global hook
hook:
    b.w query
.section .reporthook,"ax",%progbits
    b.w report_fix
.section .text,"ax",%progbits
.global query
.thumb_func
query:
    push {r4, r5, r7, lr}
    sub sp, #32
    ldr r0, .Lavrcp
.Lopen:
    add r0, pc
    ldrb r0, [r0]
    cbz r0, .Ldone
    ldr r0, .Lconnection
.Lconn:
    add r0, pc
    ldr r0, [r0]
    cbz r0, .Ldone
    ldrh.w r4, [r0, #0x1a0]
    movs r0, #8
    bl allocate
    mov r5, r0
    ldr r1, .Lcompany
    str r1, [r5]
    movs r1, #0x30       /* PDU 0x30, packet type 0, parameter length 0 */
    str r1, [r5, #4]
    movs r0, #9          /* AV/C vendor dependent opcode */
    str r0, [sp]
    movs r0, #0
    str r0, [sp, #4]
    str r0, [sp, #16]
    movs r0, #8
    str r0, [sp, #8]
    str r5, [sp, #12]
    mov r0, r4
    movs r1, #1          /* subunit ID, as used by the stock metadata request */
    movw r2, #0x110e     /* panel subunit */
    movs r3, #1          /* AV/C STATUS, not CONTROL */
    bl vendor_request
    mov r2, r0
    ldr r0, .Lqueue
.Ltask:
    add r0, pc
    ldrh r0, [r0]
    movw r1, #0x11b
    bl queue_message
    mov r0, r5
    bl release_memory
.Ldone:
    add sp, #32
    pop.w {r4, r5, r7, lr}
    /* Preserve VI's existing cached-state report and acknowledgement as well. */
    push {r7, lr}
    mov r7, sp
    b.w status_continue
.balign 4
.Lavrcp: .word avrcp_flag - (.Lopen + 4)
.Lconnection: .word connection_ptr - (.Lconn + 4)
.Lqueue: .word queue_id - (.Ltask + 4)
.Lcompany: .word 0x58190000
.global report_fix
.thumb_func
report_fix:
    /* Unknown AVRCP values must not become position 0 when BC03 divides by 1000. */
    ldr r0, [r4]
    adds r0, #1
    beq .Lunknown
    ldr r0, [r4, #4]
    adds r0, #1
    beq .Lunknown
    ldr r1, .Ltemplate_offset
.Ltemplate_pc:
    add r1, pc
    b.w report_continue
.Lunknown:
    b.w report_exit
.balign 4
.Ltemplate_offset: .word report_template - (.Ltemplate_pc + 4)
.global report_template
report_template: .asciz "MQ[playstatus_data]"
