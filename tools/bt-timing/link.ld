ENTRY(query)
SECTIONS {
  .reporthook 0x8709c : { *(.reporthook) }
  .hook 0x89d0c : { *(.hook) }
  .text 0x180000 : { *(.text) }
  /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes*) *(.comment*) }
}
allocate = 0x82161;
release_memory = 0x8220d;
vendor_request = 0xb746f;
queue_message = 0x82aa1;
/* Same connected gate used by the stock GetElementAttributes request at 0xa1db0. */
avrcp_flag = 0x165038;
connection_ptr = 0x16b090;
queue_id = 0x15ab7a;
report_continue = 0x870ad;
report_exit = 0x870e1;
status_continue = 0x89d11;
