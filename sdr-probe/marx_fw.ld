ENTRY(marx_helper)
SECTIONS
{
  . = 0x002A8000;
  .text : { *(.text.marx_helper) *(.text*) *(.rodata*) }
  /DISCARD/ : { *(.comment*) *(.ARM.exidx*) *(.ARM.attributes*) }
}
