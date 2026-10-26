/* Original Corn Flashing RAM routine. PolyForm Noncommercial 1.0.0.
 * Cortex-M3 Thumb, no stack. R0=Flash destination, R1=RAM source,
 * R2=positive halfword count. Host enables FLASH_CR.PG and masks interrupts.
 * R2=remaining, R3=last FLASH_SR at the terminal BKPT.
 * FLASH_SR bits: BSY=0, PGERR=2, WRPRTERR=4, EOP=5 (STM32 PM0075).
 */
.syntax unified
.cpu cortex-m3
.thumb
.section .text
.global corn_f1_loader
.type corn_f1_loader,%function
corn_f1_loader:
    movs r3, #0
    cmp r2, #0
    beq corn_f1_done
    ldr r4, =0x4002200c
    movs r6, #0x14
    movs r7, #1
1:
    movs r5, #0x34
    str r5, [r4]
    ldrh r5, [r1]
    strh r5, [r0]
2:
    ldr r3, [r4]
    tst r3, r7
    bne 2b
    tst r3, r6
    bne corn_f1_done
    adds r0, #2
    adds r1, #2
    subs r2, #1
    bne 1b
corn_f1_done:
    bkpt #0
    b corn_f1_done
.size corn_f1_loader, .-corn_f1_loader
.ltorg
