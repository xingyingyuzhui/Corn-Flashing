ENTRY(Init)
SECTIONS {
  . = 0;
  PrgCode : { *(PrgCode) }
  PrgData : { *(PrgData) }
  . = 0x1000;
  FlashInfo : { *(FlashInfo) }
  /DISCARD/ : { *(.ARM.attributes) *(.comment) }
}
