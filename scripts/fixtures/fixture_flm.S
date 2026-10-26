/* Original FLM fixture. Does not erase or program Flash. Test use only. */
.syntax unified
.cpu cortex-m3
.thumb
.section PrgCode,"ax",%progbits
.macro success name
.global \name
.type \name,%function
.thumb_func
\name:
    movs r0, #0
    bx lr
.size \name, .-\name
.endm
success Init
success UnInit
success EraseSector
success ProgramPage
.section PrgData,"aw",%progbits
.global fixture_state
fixture_state: .word 0x12345678
.section FlashInfo,"a",%progbits
.balign 4
.global FlashDevice
.type FlashDevice,%object
FlashDevice:
    .hword 0x0101
    .asciz "Corn FLM test fixture - NO FLASH WRITES"
    .zero 128-(.-FlashDevice-2)
    .hword 1
    .word 0x08000000
    .word 0x00040000
    .word 256
    .word 0
    .byte 0xff
    .zero 3
    .word 100
    .word 3000
    .word 2048,0
    .word 0xffffffff,0xffffffff
.size FlashDevice, .-FlashDevice
