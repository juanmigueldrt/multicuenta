# Mechanics & Extracted Constants (`Franz-setup.exe`)

## 1. Decompiled Binary Constants (`C:\Users\Agora\Downloads\Franz-setup.exe`)

The target executable is a 32-bit x86 Windows PE binary (`sha256: b047bd713f6c4df010221b3ed8803130e28fc1ecf91da58ac23925a8d4310790`) packaging **Franz 6.11.1** inside a Nullsoft Install System (NSIS v3.04) self-extracting archive. Static analysis and Ghidra decompilation of the binary's functions yielded the following constants, formulas, and runtime rules:

| Name | Group | Value | Unit | Source / Function Address | Notes |
|---|---|---|---|---|---|
| `error_mode_flags` | `runtime` | `32769` | `flags` | `function 0x40338f` | `SetErrorMode(0x8001)` in `entry` (`0x40338f`) |
| `temp_path_buffer_chars` | `storage` | `8192` | `chars` | `function 0x40338f` | `GetTempPathW(0x2000, ...)` buffer size in `entry` (`0x40338f`) |
| `temp_drive_retry_count` | `storage` | `26` | `count` | `function 0x40338f` | Drive fallback loop counter `0x1a` (`26`) in `entry` (`0x40338f`) |
| `splash_delay_ms` | `timing` | `1000` | `ms` | `function 0x402edd` | `GetTickCount() + 1000` initial delay in `FUN_00402edd` (`0x402edd`) |
| `header_scan_step_bytes` | `archive` | `512` | `bytes` | `function 0x402edd` | `0x200` byte alignment step when scanning for `NullsoftInst` header in `FUN_00402edd` (`0x402edd`) |
| `header_signature_size` | `archive` | `28` | `bytes` | `function 0x402edd` | `0x1c` byte header signature check in `FUN_00402edd` (`0x402edd`) |
| `decompress_buffer_bytes` | `archive` | `32768` | `bytes` | `function 0x403116` | `0x8000` output buffer allocation in `FUN_00403116` (`0x403116`) |
| `chunk_read_bytes` | `archive` | `16384` | `bytes` | `function 0x403116` | `0x4000` compressed chunk read size in `FUN_00403116` (`0x403116`) |
| `progress_refresh_ms` | `timing` | `200` | `ms` | `function 0x403116` | `200 < DVar3 - local_14` throttle interval (`MulDiv(done, 100, total)`) in `FUN_00403116` (`0x403116`) |
| `gradient_band_height` | `ui` | `4` | `px` | `function 0x401000` | `local_20.top + 4` vertical linear interpolation band height in `FUN_00401000` (`0x401000`) |
| `header_text_pad_left` | `ui` | `16` | `px` | `function 0x401000` | `local_20.left = 0x10` (`16`) in `FUN_00401000` (`0x401000`) |
| `header_text_pad_top` | `ui` | `8` | `px` | `function 0x401000` | `local_20.top = 8` (`8`) in `FUN_00401000` (`0x401000`) |
| `string_slot_stride` | `memory` | `16384` | `bytes` | `function 0x401434` | `0x4000` stride per VM string slot in `FUN_00401434` (`0x401434`) |
| `max_register_slots` | `memory` | `32` | `count` | `function 0x401434` | `0x1f < uVar27` (`32` slots `0..31`) in `FUN_00401434` (`0x401434`) |
| `ui_buffer_capacity` | `memory` | `65536` | `bytes` | `function 0x4039aa` | `_DAT_0047af9c = 0x10000` (`65536`) in `FUN_004039aa` (`0x4039aa`) |

### Formulas Found in Code
1. **Background Gradient Interpolation (`function 0x401000`)**:
   - Draws horizontal strips of height `gradient_band_height = 4` px across window height `H`:
   - `R(y) = (R_top * (H - y) + R_bottom * y) / H`
   - `G(y) = (G_top * (H - y) + G_bottom * y) / H`
   - `B(y) = (B_top * (H - y) + B_bottom * y) / H`
2. **Progress Percentage (`function 0x403116`)**:
   - Updated every `progress_refresh_ms = 200` ms using integer scaling: `pct = ((total - remaining) * 100) / total`.

---

## 2. Simplified WhatsApp Multi-Session Architecture (Estimated Values)

Because the inner Electron `app.asar` payload is LZMA-packed inside the 659 MB NSIS archive and the user requested a simpler app focused exclusively on **WhatsApp** with **isolated and persistent sessions**, the following layout and session parameters are estimated and implemented directly in the Rust (`eframe`/`egui`) application:

| Name | Group | Value | Unit | Status | Notes |
|---|---|---|---|---|---|
| `window_width` | `layout` | `1180` | `px` | `estimated` | Default main window width |
| `window_height` | `layout` | `760` | `px` | `estimated` | Default main window height |
| `account_rail_width` | `layout` | `76` | `px` | `estimated` | Franz/Rambox-style vertical rail: one icon per isolated WhatsApp or Telegram account, "+" adds accounts |
| `chat_list_width` | `layout` | `310` | `px` | `estimated` | Left column listing contacts/groups, search, and filters per session |
| `info_panel_width` | `layout` | `260` | `px` | `estimated` | Right-side session & contact isolation details drawer |
| `session_isolation` | `sessions` | `true` | - | `estimated` | Each WhatsApp account has its own independent partition directory, cookie jar ID, storage key, and contact/message state |
| `session_persistence` | `sessions` | `true` | - | `estimated` | Every session is saved to disk under its own isolated folder (`sessions/<session_id>/session.json`) and restored on launch |
| `default_session_count` | `sessions` | `3` | `count` | `estimated` | Pre-configured isolated WhatsApp workspaces (Personal, Trabajo, Soporte) |
| `service_mode` | `features` | `"whatsapp_telegram"` | - | `estimated` | WhatsApp and Telegram accounts, each isolated; service stored per session (META field 17, default WhatsApp). Slack/Discord/Gmail omitted |
