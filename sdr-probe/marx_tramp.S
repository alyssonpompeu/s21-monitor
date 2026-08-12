.syntax unified
.thumb
.global marx_tramp
.type marx_tramp,%function
marx_tramp:
    mov r0, r6
    mov r1, r7
    mov r2, r8
    bl marx_helper_abs
    movs r0, #0
    b.w marx_epilogue_abs
