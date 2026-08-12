ENTRY(marx_tramp)
marx_helper_abs = 0x002A8001;
marx_epilogue_abs = 0x002A8BD5;
SECTIONS
{
  . = 0x002A923C;
  .text : { *(.text*) }
  /DISCARD/ : { *(.comment*) *(.ARM.exidx*) *(.ARM.attributes*) }
}
