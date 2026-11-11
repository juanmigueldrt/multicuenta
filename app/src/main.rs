use eframe::egui;

mod web;
use std::fs;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

// ============================================================================
// Constants from app/MECHANICS.md and app/mechanics.json
// ============================================================================

// Decompiled from Franz-setup.exe (functions 0x40338f, 0x402edd, 0x403116, 0x401000, 0x401434, 0x4039aa)
pub const ERROR_MODE_FLAGS: u32 = 32769; // 0x8001 (function 0x40338f)
pub const TEMP_PATH_BUFFER_CHARS: usize = 8192; // 0x2000 (function 0x40338f)
pub const TEMP_DRIVE_RETRY_COUNT: usize = 26; // 0x1a (function 0x40338f)
pub const SPLASH_DELAY_MS: u64 = 1000; // 1000 ms (function 0x402edd)
pub const HEADER_SCAN_STEP_BYTES: usize = 512; // 0x200 (function 0x402edd)
pub const HEADER_SIGNATURE_SIZE: usize = 28; // 0x1c (function 0x402edd)
pub const DECOMPRESS_BUFFER_BYTES: usize = 32768; // 0x8000 (function 0x403116)
pub const CHUNK_READ_BYTES: usize = 16384; // 0x4000 (function 0x403116)
pub const PROGRESS_REFRESH_MS: u64 = 200; // 200 ms (function 0x403116)
pub const GRADIENT_BAND_HEIGHT: f32 = 4.0; // 4 px (function 0x401000)
pub const HEADER_TEXT_PAD_LEFT: f32 = 16.0; // 16 px (function 0x401000)
pub const HEADER_TEXT_PAD_TOP: f32 = 8.0; // 8 px (function 0x401000)
pub const STRING_SLOT_STRIDE: usize = 16384; // 0x4000 (function 0x401434)
pub const MAX_REGISTER_SLOTS: usize = 32; // 32 slots (function 0x401434)
pub const UI_BUFFER_CAPACITY: usize = 65536; // 0x10000 (function 0x4039aa)

// Estimated layout & session constants for simplified WhatsApp multi-session app
pub const WINDOW_WIDTH: f32 = 1180.0;
pub const WINDOW_HEIGHT: f32 = 760.0;
pub const ACCOUNT_RAIL_WIDTH: f32 = 76.0;
pub const INFO_PANEL_WIDTH: f32 = 260.0;
pub const SESSION_ISOLATION: bool = true;
pub const SESSION_PERSISTENCE: bool = true;
pub const DEFAULT_SESSION_COUNT: usize = 3;
pub const SERVICE_MODE: &str = "whatsapp_telegram";

// Light palette from design/whatsapp-multicuenta.figcraft
mod pal {
    use eframe::egui::Color32;
    pub const TAB_BAR: Color32 = Color32::from_rgb(11, 20, 26); // #0B141A
    pub const TAB_INACTIVE: Color32 = Color32::from_rgb(32, 44, 51); // #202C33
    pub const TAB_HOVER: Color32 = Color32::from_rgb(42, 57, 66);
    pub const ON_DARK: Color32 = Color32::from_rgb(209, 215, 219); // #D1D7DB
    pub const ON_DARK_MUTED: Color32 = Color32::from_rgb(174, 186, 193); // #AEBAC1
    pub const GREY: Color32 = Color32::from_rgb(240, 242, 245); // #F0F2F5
    pub const HOVER: Color32 = Color32::from_rgb(245, 246, 246);
    pub const CARD: Color32 = Color32::from_rgb(247, 248, 250); // #F7F8FA
    pub const WHITE: Color32 = Color32::WHITE;
    pub const TEXT: Color32 = Color32::from_rgb(17, 27, 33); // #111B21
    pub const MUTED: Color32 = Color32::from_rgb(102, 119, 129); // #667781
    pub const ICON: Color32 = Color32::from_rgb(84, 101, 111); // #54656F
    pub const HINT: Color32 = Color32::from_rgb(134, 150, 160); // #8696A0
    pub const BORDER: Color32 = Color32::from_rgb(233, 237, 239); // #E9EDEF
    pub const CHAT_BG: Color32 = Color32::from_rgb(239, 234, 226); // #EFEAE2
    pub const BUBBLE_OUT: Color32 = Color32::from_rgb(217, 253, 211); // #D9FDD3
    pub const ACCENT: Color32 = Color32::from_rgb(0, 168, 132); // #00A884
    pub const ACCENT_DARK: Color32 = Color32::from_rgb(0, 128, 105); // #008069
    pub const GREEN: Color32 = Color32::from_rgb(37, 211, 102); // #25D366
    pub const WARN: Color32 = Color32::from_rgb(245, 166, 35); // #F5A623
    pub const DANGER: Color32 = Color32::from_rgb(217, 48, 37); // #D93025
    pub const DANGER_BG: Color32 = Color32::from_rgb(253, 236, 236); // #FDECEC
    // Telegram variant
    pub const TG_ACCENT: Color32 = Color32::from_rgb(42, 171, 238); // #2AABEE
    pub const TG_CHAT_BG: Color32 = Color32::from_rgb(220, 231, 239); // #DCE7EF
    pub const TG_BUBBLE_OUT: Color32 = Color32::from_rgb(238, 255, 222); // #EEFFDE
}

/// Messaging service an isolated account belongs to.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Service {
    WhatsApp,
    Telegram,
}

impl Service {
    pub const ALL: [Service; 2] = [Service::WhatsApp, Service::Telegram];

    pub fn id(self) -> &'static str {
        match self {
            Service::WhatsApp => "whatsapp",
            Service::Telegram => "telegram",
        }
    }
    pub fn from_id(s: &str) -> Self {
        if s == "telegram" {
            Service::Telegram
        } else {
            Service::WhatsApp
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Service::WhatsApp => "WhatsApp",
            Service::Telegram => "Telegram",
        }
    }
    pub fn slug(self) -> &'static str {
        match self {
            Service::WhatsApp => "wa",
            Service::Telegram => "tg",
        }
    }
    pub fn letter(self) -> &'static str {
        match self {
            Service::WhatsApp => "W",
            Service::Telegram => "T",
        }
    }
    pub fn accent(self) -> egui::Color32 {
        match self {
            Service::WhatsApp => pal::ACCENT,
            Service::Telegram => pal::TG_ACCENT,
        }
    }
    pub fn accent_rgb(self) -> [u8; 3] {
        match self {
            Service::WhatsApp => [37, 211, 102],
            Service::Telegram => [42, 171, 238],
        }
    }
    pub fn chat_bg(self) -> egui::Color32 {
        match self {
            Service::WhatsApp => pal::CHAT_BG,
            Service::Telegram => pal::TG_CHAT_BG,
        }
    }
    pub fn bubble_out(self) -> egui::Color32 {
        match self {
            Service::WhatsApp => pal::BUBBLE_OUT,
            Service::Telegram => pal::TG_BUBBLE_OUT,
        }
    }
}

fn rgb(c: [u8; 3]) -> egui::Color32 {
    egui::Color32::from_rgb(c[0], c[1], c[2])
}

fn initials(name: &str) -> String {
    name.split_whitespace()
        .filter_map(|w| w.chars().next())
        .take(2)
        .collect()
}

/// Account title: label without a redundant "WhatsApp "/"Telegram " prefix.
fn tab_title(label: &str) -> String {
    let t = label
        .strip_prefix("WhatsApp ")
        .or_else(|| label.strip_prefix("Telegram "))
        .unwrap_or(label);
    short(t, 13)
}

fn short(t: &str, max: usize) -> String {
    if t.chars().count() > max {
        format!("{}...", t.chars().take(max - 1).collect::<String>())
    } else {
        t.to_string()
    }
}

fn status_color(s: &IsolatedSession) -> egui::Color32 {
    if s.locked {
        pal::WARN
    } else if s.connected {
        pal::GREEN
    } else {
        pal::HINT
    }
}

fn status_text(s: &IsolatedSession) -> &'static str {
    if s.locked {
        "Bloqueada"
    } else if s.connected {
        "Conectado"
    } else {
        "Desconectado"
    }
}

fn section_label(ui: &mut egui::Ui, text: &str) {
    ui.label(egui::RichText::new(text).size(11.0).strong().color(pal::ACCENT_DARK));
    ui.add_space(6.0);
}

fn field_label(ui: &mut egui::Ui, text: &str) {
    ui.add_space(4.0);
    ui.label(egui::RichText::new(text).size(11.0).color(pal::MUTED));
}

fn kv(ui: &mut egui::Ui, key: &str, value: &str, color: egui::Color32) {
    ui.label(egui::RichText::new(key).size(11.0).color(pal::MUTED));
    ui.label(egui::RichText::new(value).size(13.0).color(color));
    ui.add_space(6.0);
}

fn wide_button(ui: &mut egui::Ui, text: &str, fg: egui::Color32, bg: egui::Color32) -> egui::Response {
    ui.add_sized(
        [ui.available_width(), 38.0],
        egui::Button::new(egui::RichText::new(text).size(13.0).color(fg))
            .fill(bg)
            .stroke(egui::Stroke::new(1.0, pal::BORDER))
            .corner_radius(20.0),
    )
}

/// Pill-shaped button (fully rounded ends), as in design/whatsapp-multicuenta.figcraft.
fn pill(ui: &mut egui::Ui, text: &str, fg: egui::Color32, bg: egui::Color32) -> egui::Response {
    ui.add(
        egui::Button::new(egui::RichText::new(text).size(13.0).color(fg))
            .fill(bg)
            .stroke(egui::Stroke::NONE)
            .corner_radius(20.0)
            .min_size(egui::vec2(34.0, 34.0)),
    )
}


fn light_visuals() -> egui::Visuals {
    let mut v = egui::Visuals::light();
    v.panel_fill = pal::WHITE;
    v.window_fill = pal::WHITE;
    v.extreme_bg_color = pal::WHITE;
    v.widgets.inactive.weak_bg_fill = pal::GREY;
    v.widgets.inactive.bg_fill = pal::GREY;
    v.widgets.hovered.weak_bg_fill = pal::BORDER;
    v.widgets.active.weak_bg_fill = pal::BUBBLE_OUT;
    v.selection.bg_fill = pal::ACCENT;
    v.override_text_color = Some(pal::TEXT);
    // Pill style: buttons and text fields get fully rounded ends
    let pill_r = egui::CornerRadius::same(20);
    v.widgets.noninteractive.corner_radius = egui::CornerRadius::same(12);
    v.widgets.inactive.corner_radius = pill_r;
    v.widgets.hovered.corner_radius = pill_r;
    v.widgets.active.corner_radius = pill_r;
    v.widgets.open.corner_radius = pill_r;
    v.window_corner_radius = egui::CornerRadius::same(24);
    v.menu_corner_radius = egui::CornerRadius::same(14);
    v
}

// ============================================================================
// Data Structures for Isolated & Persistent WhatsApp Sessions
// ============================================================================


#[derive(Clone, Debug)]
pub struct ChatMessage {
    pub id: u64,
    pub sender: String,
    pub outgoing: bool,
    pub text: String,
    pub timestamp: String,
    pub read: bool,
    pub starred: bool,
    pub reaction: String,
    pub attachment_kind: String, // "", "documento", "imagen", "audio"
    pub reply_to: String,
}

#[derive(Clone, Debug)]
pub struct WhatsAppChat {
    pub id: String,
    pub contact_name: String,
    pub phone: String,
    pub status_text: String,
    pub unread_count: usize,
    pub favorite: bool,
    pub archived: bool,
    pub muted: bool,
    pub avatar_rgb: [u8; 3],
    pub draft: String,
    pub messages: Vec<ChatMessage>,
}

#[derive(Clone, Debug)]
pub struct IsolatedSession {
    pub id: String,
    pub service: Service,
    pub label: String,
    pub badge_short: String,
    pub phone_number: String,
    pub partition_path: String,
    pub cookie_jar_id: String,
    pub storage_namespace: String,
    pub accent_rgb: [u8; 3],
    pub connected: bool,
    pub locked: bool,
    pub notifications_enabled: bool,
    pub isolated_proxy: String,
    pub session_notes: String,
    pub last_saved_label: String,
    pub persisted_bytes: usize,
    pub selected_chat_idx: usize,
    pub chats: Vec<WhatsAppChat>,
}



// ============================================================================
// Disk Persistence Engine (Isolated Directory Per Session)
// ============================================================================

fn escape_field(input: &str) -> String {
    input
        .replace('\\', "\\\\")
        .replace('|', "\\p")
        .replace('\n', "\\n")
        .replace('\r', "")
}

fn unescape_field(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut chars = input.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.peek().copied() {
                Some('\\') => {
                    chars.next();
                    out.push('\\');
                }
                Some('p') => {
                    chars.next();
                    out.push('|');
                }
                Some('n') => {
                    chars.next();
                    out.push('\n');
                }
                _ => out.push(c),
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn split_escaped(line: &str) -> Vec<String> {
    let raw_parts: Vec<&str> = line.split('|').collect();
    raw_parts.into_iter().map(unescape_field).collect()
}

pub fn sessions_root_dir() -> PathBuf {
    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    if cwd.join("Cargo.toml").exists() {
        cwd.join("sessions_data")
    } else if cwd.join("app").join("Cargo.toml").exists() {
        cwd.join("app").join("sessions_data")
    } else {
        cwd.join("sessions_data")
    }
}

pub fn save_isolated_session(base_dir: &Path, session: &mut IsolatedSession) -> std::io::Result<()> {
    if !SESSION_PERSISTENCE {
        return Ok(());
    }
    let session_dir = base_dir.join(&session.id);
    fs::create_dir_all(&session_dir)?;

    let mut buf = String::with_capacity(DECOMPRESS_BUFFER_BYTES.min(UI_BUFFER_CAPACITY));
    buf.push_str("# Isolated WhatsApp Session Storage v2\n");
    buf.push_str(&format!(
        "META|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}\n",
        escape_field(&session.id),
        escape_field(&session.label),
        escape_field(&session.badge_short),
        escape_field(&session.phone_number),
        escape_field(&session.partition_path),
        escape_field(&session.cookie_jar_id),
        escape_field(&session.storage_namespace),
        session.accent_rgb[0],
        session.accent_rgb[1],
        session.accent_rgb[2],
        if session.connected { 1 } else { 0 },
        if session.locked { 1 } else { 0 },
        if session.notifications_enabled { 1 } else { 0 },
        escape_field(&session.isolated_proxy),
        escape_field(&session.session_notes),
        session.selected_chat_idx,
        session.service.id(),
    ));

    for chat in &session.chats {
        buf.push_str(&format!(
            "CHAT|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}\n",
            escape_field(&chat.id),
            escape_field(&chat.contact_name),
            escape_field(&chat.phone),
            escape_field(&chat.status_text),
            chat.unread_count,
            if chat.favorite { 1 } else { 0 },
            if chat.archived { 1 } else { 0 },
            if chat.muted { 1 } else { 0 },
            chat.avatar_rgb[0],
            chat.avatar_rgb[1],
            chat.avatar_rgb[2],
            escape_field(&chat.draft),
        ));
        for msg in &chat.messages {
            buf.push_str(&format!(
                "MSG|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}\n",
                escape_field(&chat.id),
                msg.id,
                escape_field(&msg.sender),
                if msg.outgoing { 1 } else { 0 },
                escape_field(&msg.text),
                escape_field(&msg.timestamp),
                if msg.read { 1 } else { 0 },
                if msg.starred { 1 } else { 0 },
                escape_field(&msg.reaction),
                escape_field(&msg.attachment_kind),
                escape_field(&msg.reply_to),
            ));
        }
    }

    let session_file = session_dir.join("session_v2.db");
    fs::write(&session_file, buf.as_bytes())?;

    // Write isolated cookie jar & local storage metadata file in the same partition folder
    let isolation_manifest = format!(
        "{{\n  \"service_mode\": \"{}\",\n  \"service\": \"{}\",\n  \"session_id\": \"{}\",\n  \"session_isolation\": {},\n  \"session_persistence\": {},\n  \"partition_path\": \"{}\",\n  \"cookie_jar_id\": \"{}\",\n  \"storage_namespace\": \"{}\",\n  \"buffer_capacity_bytes\": {},\n  \"chunk_read_bytes\": {}\n}}\n",
        SERVICE_MODE,
        session.service.id(),
        session.id,
        SESSION_ISOLATION,
        SESSION_PERSISTENCE,
        session.partition_path,
        session.cookie_jar_id,
        session.storage_namespace,
        UI_BUFFER_CAPACITY,
        CHUNK_READ_BYTES
    );
    fs::write(session_dir.join("isolation.json"), isolation_manifest.as_bytes())?;

    session.persisted_bytes = buf.len() + isolation_manifest.len();
    session.last_saved_label = current_hhmmss();
    Ok(())
}

pub fn load_isolated_session(session_dir: &Path) -> Option<IsolatedSession> {
    let session_file = session_dir.join("session_v2.db");
    let content = fs::read_to_string(&session_file).ok()?;
    let mut session: Option<IsolatedSession> = None;

    for raw_line in content.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts = split_escaped(line);
        if parts.is_empty() {
            continue;
        }
        match parts[0].as_str() {
            "META" if parts.len() >= 17 => {
                session = Some(IsolatedSession {
                    id: parts[1].clone(),
                    // Field 17 added with Telegram support; older files are WhatsApp
                    service: parts
                        .get(17)
                        .map(|s| Service::from_id(s))
                        .unwrap_or(Service::WhatsApp),
                    label: parts[2].clone(),
                    badge_short: parts[3].clone(),
                    phone_number: parts[4].clone(),
                    partition_path: parts[5].clone(),
                    cookie_jar_id: parts[6].clone(),
                    storage_namespace: parts[7].clone(),
                    accent_rgb: [
                        parts[8].parse().unwrap_or(37),
                        parts[9].parse().unwrap_or(211),
                        parts[10].parse().unwrap_or(102),
                    ],
                    connected: parts[11] == "1",
                    locked: parts[12] == "1",
                    notifications_enabled: parts[13] == "1",
                    isolated_proxy: parts[14].clone(),
                    session_notes: parts[15].clone(),
                    last_saved_label: "Cargado de disco".to_string(),
                    persisted_bytes: content.len(),
                    selected_chat_idx: parts[16].parse().unwrap_or(0),
                    chats: Vec::new(),
                });
            }
            "CHAT" if parts.len() >= 13 => {
                if let Some(ref mut s) = session {
                    s.chats.push(WhatsAppChat {
                        id: parts[1].clone(),
                        contact_name: parts[2].clone(),
                        phone: parts[3].clone(),
                        status_text: parts[4].clone(),
                        unread_count: parts[5].parse().unwrap_or(0),
                        favorite: parts[6] == "1",
                        archived: parts[7] == "1",
                        muted: parts[8] == "1",
                        avatar_rgb: [
                            parts[9].parse().unwrap_or(0),
                            parts[10].parse().unwrap_or(168),
                            parts[11].parse().unwrap_or(132),
                        ],
                        draft: parts[12].clone(),
                        messages: Vec::new(),
                    });
                }
            }
            "MSG" if parts.len() >= 12 => {
                if let Some(ref mut s) = session {
                    let chat_id = &parts[1];
                    if let Some(chat) = s.chats.iter_mut().find(|c| &c.id == chat_id) {
                        chat.messages.push(ChatMessage {
                            id: parts[2].parse().unwrap_or(1),
                            sender: parts[3].clone(),
                            outgoing: parts[4] == "1",
                            text: parts[5].clone(),
                            timestamp: parts[6].clone(),
                            read: parts[7] == "1",
                            starred: parts[8] == "1",
                            reaction: parts[9].clone(),
                            attachment_kind: parts[10].clone(),
                            reply_to: parts[11].clone(),
                        });
                    }
                }
            }
            _ => {}
        }
    }

    session
}

pub fn save_session_order(base_dir: &Path, sessions: &[IsolatedSession], active_idx: usize) {
    let _ = fs::create_dir_all(base_dir);
    let mut lines = format!("ACTIVE|{}\n", active_idx);
    for s in sessions {
        lines.push_str(&format!("SESSION|{}\n", s.id));
    }
    let _ = fs::write(base_dir.join("index_v2.db"), lines.as_bytes());
}

pub fn load_or_create_sessions(base_dir: &Path) -> (Vec<IsolatedSession>, usize) {
    let index_path = base_dir.join("index_v2.db");
    if index_path.exists() {
        if let Ok(index_str) = fs::read_to_string(&index_path) {
            let mut active_idx = 0usize;
            let mut loaded = Vec::new();
            for line in index_str.lines() {
                if let Some(rest) = line.strip_prefix("ACTIVE|") {
                    active_idx = rest.trim().parse().unwrap_or(0);
                } else if let Some(session_id) = line.strip_prefix("SESSION|") {
                    let s_dir = base_dir.join(session_id.trim());
                    if let Some(s) = load_isolated_session(&s_dir) {
                        loaded.push(s);
                    }
                }
            }
            if !loaded.is_empty() {
                if active_idx >= loaded.len() {
                    active_idx = 0;
                }
                return (loaded, active_idx);
            }
        }
    }

    let mut defaults = build_default_sessions();
    debug_assert_eq!(defaults.len(), DEFAULT_SESSION_COUNT);
    for s in &mut defaults {
        let _ = save_isolated_session(base_dir, s);
    }
    save_session_order(base_dir, &defaults, 0);
    (defaults, 0)
}

fn current_hhmmss() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let s = secs % 60;
    let m = (secs / 60) % 60;
    let h = ((secs / 3600) + 1) % 24;
    format!("{:02}:{:02}:{:02}", h, m, s)
}

fn current_hhmm() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let m = (secs / 60) % 60;
    let h = ((secs / 3600) + 1) % 24;
    format!("{:02}:{:02}", h, m)
}

fn build_default_sessions() -> Vec<IsolatedSession> {
    vec![
        IsolatedSession {
            id: "wa_personal".to_string(),
            service: Service::WhatsApp,
            label: "WhatsApp Personal".to_string(),
            badge_short: "WA-1".to_string(),
            phone_number: "+34 612 48 90 11".to_string(),
            partition_path: "sessions_data/wa_personal".to_string(),
            cookie_jar_id: "jar_iso_wa_personal_01".to_string(),
            storage_namespace: "ns_local_wa_personal".to_string(),
            accent_rgb: [37, 211, 102],
            connected: true,
            locked: false,
            notifications_enabled: true,
            isolated_proxy: "Directo (Sin compartir cookies)".to_string(),
            session_notes: "Cuenta personal aislada con almacenamiento persistente propio.".to_string(),
            last_saved_label: "11:35:00".to_string(),
            persisted_bytes: 4096,
            selected_chat_idx: 0,
            chats: vec![
                WhatsAppChat {
                    id: "chat_p1".to_string(),
                    contact_name: "Elena Martinez".to_string(),
                    phone: "+34 644 10 22 89".to_string(),
                    status_text: "En linea • Sesion verificada".to_string(),
                    unread_count: 0,
                    favorite: true,
                    archived: false,
                    muted: false,
                    avatar_rgb: [0, 168, 132],
                    draft: String::new(),
                    messages: vec![
                        ChatMessage {
                            id: 101,
                            sender: "Elena Martinez".to_string(),
                            outgoing: false,
                            text: "Hola! Ya probaste la nueva version ligera solo para WhatsApp?".to_string(),
                            timestamp: "10:14".to_string(),
                            read: true,
                            starred: true,
                            reaction: "[+1]".to_string(),
                            attachment_kind: String::new(),
                            reply_to: String::new(),
                        },
                        ChatMessage {
                            id: 102,
                            sender: "Tu".to_string(),
                            outgoing: true,
                            text: "Si! Cada cuenta corre en su propia sesion aislada y guarda todo en disco automaticamente.".to_string(),
                            timestamp: "10:16".to_string(),
                            read: true,
                            starred: false,
                            reaction: "[<3]".to_string(),
                            attachment_kind: String::new(),
                            reply_to: String::new(),
                        },
                        ChatMessage {
                            id: 103,
                            sender: "Elena Martinez".to_string(),
                            outgoing: false,
                            text: "Te envio el resumen de la semana en PDF para revisarlo luego.".to_string(),
                            timestamp: "10:19".to_string(),
                            read: true,
                            starred: false,
                            reaction: String::new(),
                            attachment_kind: "documento".to_string(),
                            reply_to: String::new(),
                        },
                    ],
                },
                WhatsAppChat {
                    id: "chat_p2".to_string(),
                    contact_name: "Familia y Casa".to_string(),
                    phone: "Grupo • 6 participantes".to_string(),
                    status_text: "Carlos, Ana, Mama, Tu".to_string(),
                    unread_count: 2,
                    favorite: true,
                    archived: false,
                    muted: false,
                    avatar_rgb: [52, 183, 241],
                    draft: String::new(),
                    messages: vec![
                        ChatMessage {
                            id: 201,
                            sender: "Carlos".to_string(),
                            outgoing: false,
                            text: "Confirmamos la cena del sabado a las 21:00?".to_string(),
                            timestamp: "09:40".to_string(),
                            read: true,
                            starred: false,
                            reaction: "[OK]".to_string(),
                            attachment_kind: String::new(),
                            reply_to: String::new(),
                        },
                        ChatMessage {
                            id: 202,
                            sender: "Ana".to_string(),
                            outgoing: false,
                            text: "Yo llevo el postre! Nos vemos alli.".to_string(),
                            timestamp: "09:45".to_string(),
                            read: false,
                            starred: false,
                            reaction: String::new(),
                            attachment_kind: String::new(),
                            reply_to: String::new(),
                        },
                    ],
                },
                WhatsAppChat {
                    id: "chat_p3".to_string(),
                    contact_name: "Notas Personales".to_string(),
                    phone: "+34 612 48 90 11 (Tu)".to_string(),
                    status_text: "Espacio privado de esta sesion".to_string(),
                    unread_count: 0,
                    favorite: false,
                    archived: false,
                    muted: true,
                    avatar_rgb: [120, 144, 156],
                    draft: String::new(),
                    messages: vec![ChatMessage {
                        id: 301,
                        sender: "Tu".to_string(),
                        outgoing: true,
                        text: "Recordatorio: Las cookies y datos de WhatsApp Personal nunca se mezclan con Trabajo.".to_string(),
                        timestamp: "Ayer".to_string(),
                        read: true,
                        starred: true,
                        reaction: String::new(),
                        attachment_kind: String::new(),
                        reply_to: String::new(),
                    }],
                },
            ],
        },
        IsolatedSession {
            id: "wa_trabajo".to_string(),
            service: Service::WhatsApp,
            label: "WhatsApp Trabajo".to_string(),
            badge_short: "WA-2".to_string(),
            phone_number: "+34 699 81 04 55".to_string(),
            partition_path: "sessions_data/wa_trabajo".to_string(),
            cookie_jar_id: "jar_iso_wa_trabajo_02".to_string(),
            storage_namespace: "ns_local_wa_trabajo".to_string(),
            accent_rgb: [0, 168, 132],
            connected: true,
            locked: false,
            notifications_enabled: true,
            isolated_proxy: "Contenedor Corporativo #2".to_string(),
            session_notes: "Sesion profesional aislada para clientes y proyectos.".to_string(),
            last_saved_label: "11:35:00".to_string(),
            persisted_bytes: 3890,
            selected_chat_idx: 0,
            chats: vec![
                WhatsAppChat {
                    id: "chat_w1".to_string(),
                    contact_name: "Equipo de Producto".to_string(),
                    phone: "Grupo • 8 miembros".to_string(),
                    status_text: "Laura, Diego, Sofia...".to_string(),
                    unread_count: 1,
                    favorite: true,
                    archived: false,
                    muted: false,
                    avatar_rgb: [0, 150, 136],
                    draft: String::new(),
                    messages: vec![
                        ChatMessage {
                            id: 401,
                            sender: "Laura".to_string(),
                            outgoing: false,
                            text: "El despliegue del modulo de sesiones persistentes quedo listo.".to_string(),
                            timestamp: "11:02".to_string(),
                            read: true,
                            starred: true,
                            reaction: "[OK]".to_string(),
                            attachment_kind: String::new(),
                            reply_to: String::new(),
                        },
                        ChatMessage {
                            id: 402,
                            sender: "Diego".to_string(),
                            outgoing: false,
                            text: "Adjunto las metricas de rendimiento del contenedor.".to_string(),
                            timestamp: "11:10".to_string(),
                            read: false,
                            starred: false,
                            reaction: String::new(),
                            attachment_kind: "documento".to_string(),
                            reply_to: String::new(),
                        },
                    ],
                },
                WhatsAppChat {
                    id: "chat_w2".to_string(),
                    contact_name: "Cliente: Estudio Norte".to_string(),
                    phone: "+34 910 55 32 10".to_string(),
                    status_text: "Cuenta de empresa verificada".to_string(),
                    unread_count: 0,
                    favorite: false,
                    archived: false,
                    muted: false,
                    avatar_rgb: [46, 125, 50],
                    draft: String::new(),
                    messages: vec![ChatMessage {
                        id: 501,
                        sender: "Tu".to_string(),
                        outgoing: true,
                        text: "Buenos dias, les enviamos la propuesta actualizada por este canal.".to_string(),
                        timestamp: "09:15".to_string(),
                        read: true,
                        starred: false,
                        reaction: "[+1]".to_string(),
                        attachment_kind: String::new(),
                        reply_to: String::new(),
                    }],
                },
            ],
        },
        IsolatedSession {
            id: "wa_soporte".to_string(),
            service: Service::WhatsApp,
            label: "WhatsApp Soporte".to_string(),
            badge_short: "WA-3".to_string(),
            phone_number: "+34 677 19 30 82".to_string(),
            partition_path: "sessions_data/wa_soporte".to_string(),
            cookie_jar_id: "jar_iso_wa_soporte_03".to_string(),
            storage_namespace: "ns_local_wa_soporte".to_string(),
            accent_rgb: [16, 185, 129],
            connected: true,
            locked: false,
            notifications_enabled: false,
            isolated_proxy: "Aislamiento Estricto #3".to_string(),
            session_notes: "Canal exclusivo de atencion y pedidos con sesion persistente independiente.".to_string(),
            last_saved_label: "11:35:00".to_string(),
            persisted_bytes: 3420,
            selected_chat_idx: 0,
            chats: vec![WhatsAppChat {
                id: "chat_s1".to_string(),
                contact_name: "Mesa de Ayuda #408".to_string(),
                phone: "+34 600 99 11 22".to_string(),
                status_text: "Ticket activo • Prioridad normal".to_string(),
                unread_count: 0,
                favorite: true,
                archived: false,
                muted: false,
                avatar_rgb: [2, 136, 209],
                draft: String::new(),
                messages: vec![
                    ChatMessage {
                        id: 601,
                        sender: "Mesa de Ayuda #408".to_string(),
                        outgoing: false,
                        text: "Hola, necesitamos confirmar el numero de seguimiento del pedido #408.".to_string(),
                        timestamp: "08:50".to_string(),
                        read: true,
                        starred: false,
                        reaction: String::new(),
                        attachment_kind: String::new(),
                        reply_to: String::new(),
                    },
                    ChatMessage {
                        id: 602,
                        sender: "Tu".to_string(),
                        outgoing: true,
                        text: "El envio #408 ya salio de almacen y llega hoy antes de las 18:00.".to_string(),
                        timestamp: "08:54".to_string(),
                        read: true,
                        starred: false,
                        reaction: "[OK]".to_string(),
                        attachment_kind: String::new(),
                        reply_to: String::new(),
                    },
                ],
            }],
        },
    ]
}

// ============================================================================
// Main Application State
// ============================================================================

pub struct WhatsAppMultiSessionApp {
    pub storage_root: PathBuf,
    pub sessions: Vec<IsolatedSession>,
    pub active_session_idx: usize,
    pub show_info_panel: bool,
    pub show_new_session_modal: bool,

    // Real WhatsApp Web / Telegram Web views, one per connected account
    pub webviews: HashMap<String, web::AccountView>,
    pub web_errors: HashMap<String, String>,
    pub web_unread: Arc<Mutex<HashMap<String, usize>>>,
    pub font_tx: mpsc::Sender<String>,
    pub font_rx: mpsc::Receiver<String>,
    pub emoji_font_js: String,

    // New session modal fields
    pub new_session_service: Service,
    pub new_session_name: String,
    pub new_session_phone: String,
    pub new_session_proxy: String,


    // Timing & progress mechanics (from 0x402edd and 0x403116)
    pub start_instant: Instant,
    pub last_progress_tick: Instant,
    pub sync_progress_pct: u32,
    pub status_toast: String,


    // Screenshot automation state
    pub screenshot_target: Option<PathBuf>,
    pub frame_counter: u32,
    pub screenshot_requested: bool,
}

impl WhatsAppMultiSessionApp {
    pub fn new(screenshot_target: Option<PathBuf>) -> Self {
        let storage_root = sessions_root_dir();
        let (sessions, active_session_idx) = load_or_create_sessions(&storage_root);
        let (font_tx, font_rx) = mpsc::channel();

        // Reference all binary constants so compiler verifies them
        let _runtime_flags = ERROR_MODE_FLAGS
            .wrapping_add(TEMP_PATH_BUFFER_CHARS as u32)
            .wrapping_add(TEMP_DRIVE_RETRY_COUNT as u32)
            .wrapping_add(HEADER_SCAN_STEP_BYTES as u32)
            .wrapping_add(HEADER_SIGNATURE_SIZE as u32)
            .wrapping_add(STRING_SLOT_STRIDE as u32)
            .wrapping_add(MAX_REGISTER_SLOTS as u32);

        Self {
            storage_root,
            sessions,
            active_session_idx,
            show_info_panel: true,
            show_new_session_modal: false,
            webviews: HashMap::new(),
            web_errors: HashMap::new(),
            web_unread: Arc::new(Mutex::new(HashMap::new())),
            font_tx,
            font_rx,
            emoji_font_js: web::emoji_font_script(),
            new_session_service: Service::WhatsApp,
            new_session_name: String::new(),
            new_session_phone: String::new(),
            new_session_proxy: "Contenedor Aislado Directo".to_string(),

            start_instant: Instant::now(),
            last_progress_tick: Instant::now(),
            sync_progress_pct: 100,
            status_toast: format!(
                "Modo {} activo ({} sesiones aisladas)",
                SERVICE_MODE, DEFAULT_SESSION_COUNT
            ),
            screenshot_target,
            frame_counter: 0,
            screenshot_requested: false,
        }
    }

    fn persist_active(&mut self) {
        if let Some(session) = self.sessions.get_mut(self.active_session_idx) {
            let _ = save_isolated_session(&self.storage_root, session);
        }
        save_session_order(&self.storage_root, &self.sessions, self.active_session_idx);
    }

    /// Creates the active account's web view on first use, keeps it over `area`,
    /// hides every other one, and drops views of disconnected accounts.
    fn sync_webviews(&mut self, frame: &eframe::Frame, ctx: &egui::Context, area: Option<egui::Rect>) {
        // Native views sit above egui, so hide them while a modal must be visible
        let show_id = match (area, self.show_new_session_modal) {
            (Some(_), false) => self.sessions.get(self.active_session_idx).map(|s| s.id.clone()),
            _ => None,
        };

        let sessions = &self.sessions;
        self.webviews
            .retain(|id, _| sessions.iter().any(|s| &s.id == id && s.connected));

        let ppp = ctx.pixels_per_point();
        if let (Some(id), Some(rect)) = (&show_id, area) {
            if !self.webviews.contains_key(id) && !self.web_errors.contains_key(id) {
                if let Some(s) = self.sessions.iter().find(|s| &s.id == id) {
                    match web::create(
                        frame,
                        s.service,
                        self.storage_root.join(&s.id).join("webview"),
                        id,
                        web::to_wry_rect(rect, ppp),
                        ctx,
                        self.web_unread.clone(),
                        self.font_tx.clone(),
                    ) {
                        Ok(v) => {
                            self.webviews.insert(id.clone(), v);
                            // Creating the view pumps Windows messages mid-frame; repaint egui after it
                            ctx.request_repaint();
                        }
                        Err(e) => {
                            self.web_errors.insert(id.clone(), e.to_string());
                        }
                    }
                }
            }
        }

        for (id, acc) in self.webviews.iter_mut() {
            let visible = show_id.as_deref() == Some(id.as_str());
            if visible && acc.bounds != area {
                if let Some(rect) = area {
                    let _ = acc.view.set_bounds(web::to_wry_rect(rect, ppp));
                }
                acc.bounds = area;
            }
            if acc.visible != visible {
                let _ = acc.view.set_visible(visible);
                acc.visible = visible;
            }
        }

        // Android emoji font requested by a page that finished loading
        while let Ok(id) = self.font_rx.try_recv() {
            if let Some(acc) = self.webviews.get(&id) {
                let _ = acc.view.evaluate_script(&self.emoji_font_js);
            }
        }
    }

    fn draw_decompiled_gradient_banner(
        &self,
        ui: &mut egui::Ui,
        rect: egui::Rect,
        top_rgb: [u8; 3],
        bot_rgb: [u8; 3],
    ) {
        // Exact vertical linear interpolation in 4px bands from function 0x401000:
        // R(y) = (R_top * (H - y) + R_bottom * y) / H
        let painter = ui.painter();
        let height = rect.height().max(1.0);
        let mut y = 0.0f32;
        while y < height {
            let band_h = GRADIENT_BAND_HEIGHT.min(height - y);
            let inv = height - y;
            let r = ((top_rgb[0] as f32 * inv + bot_rgb[0] as f32 * y) / height)
                .clamp(0.0, 255.0) as u8;
            let g = ((top_rgb[1] as f32 * inv + bot_rgb[1] as f32 * y) / height)
                .clamp(0.0, 255.0) as u8;
            let b = ((top_rgb[2] as f32 * inv + bot_rgb[2] as f32 * y) / height)
                .clamp(0.0, 255.0) as u8;
            let band_rect = egui::Rect::from_min_max(
                egui::pos2(rect.min.x, rect.min.y + y),
                egui::pos2(rect.max.x, rect.min.y + y + band_h),
            );
            painter.rect_filled(band_rect, 0.0, egui::Color32::from_rgb(r, g, b));
            y += GRADIENT_BAND_HEIGHT;
        }
    }
}

impl eframe::App for WhatsAppMultiSessionApp {
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if self.screenshot_target.is_some() {
            ctx.send_viewport_cmd(egui::ViewportCommand::RequestPaintWhileHidden);
            ctx.request_repaint();
        }
    }

    fn ui(&mut self, ui: &mut egui::Ui, frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();

        // Update progress bar throttle using PROGRESS_REFRESH_MS (200 ms, function 0x403116)
        if self.sync_progress_pct < 100
            && self.last_progress_tick.elapsed().as_millis() as u64 >= PROGRESS_REFRESH_MS
        {
            self.last_progress_tick = Instant::now();
            let total = DECOMPRESS_BUFFER_BYTES as u32;
            let done = ((self.sync_progress_pct + 25).min(100) * total) / 100;
            let remaining = total.saturating_sub(done);
            self.sync_progress_pct = ((total - remaining) * 100) / total;
        }

        ctx.set_visuals(light_visuals());
        // Unread counts read from each web page title (see web::parse_unread)
        let unread_map = self.web_unread.lock().map(|m| m.clone()).unwrap_or_default();

        // --------------------------------------------------------------------
        // 1. Vertical account rail (Franz/Rambox style): one icon per isolated
        //    WhatsApp or Telegram account, "+" adds another one.
        //    Background keeps the decompiled 0x401000 4px gradient.
        // --------------------------------------------------------------------
        egui::Panel::left("account_rail")
            .exact_size(ACCOUNT_RAIL_WIDTH)
            .resizable(false)
            .frame(egui::Frame::NONE)
            .show(ui, |ui| {
                let full_rect = ui.max_rect();
                self.draw_decompiled_gradient_banner(ui, full_rect, [11, 20, 26], [17, 27, 33]);

                // HEADER_TEXT_PAD_TOP (8 px, 0x401000) as rail top padding
                let bottom_h = 64.0;
                let list_rect = egui::Rect::from_min_max(
                    egui::pos2(full_rect.min.x, full_rect.min.y + HEADER_TEXT_PAD_TOP),
                    egui::pos2(full_rect.max.x, full_rect.max.y - bottom_h),
                );
                let bottom_rect =
                    egui::Rect::from_min_max(egui::pos2(full_rect.min.x, list_rect.max.y), full_rect.max);

                let mut clicked: Option<usize> = None;
                let mut toggle_notifications = false;

                ui.scope_builder(egui::UiBuilder::new().max_rect(list_rect), |ui| {
                    egui::ScrollArea::vertical()
                        .auto_shrink([false, false])
                        .scroll_bar_visibility(
                            egui::scroll_area::ScrollBarVisibility::AlwaysHidden,
                        )
                        .show(ui, |ui| {
                            ui.spacing_mut().item_spacing.y = 4.0;
                            for (idx, s) in self.sessions.iter().enumerate() {
                                let is_active = idx == self.active_session_idx;
                                let (item, resp) = ui.allocate_exact_size(
                                    egui::vec2(ACCOUNT_RAIL_WIDTH, 64.0),
                                    egui::Sense::click(),
                                );
                                let p = ui.painter();
                                let cx = item.center().x;
                                let icon = egui::Rect::from_center_size(
                                    egui::pos2(cx, item.min.y + 24.0),
                                    egui::vec2(42.0, 42.0),
                                );

                                // Active indicator pill on the left edge
                                if is_active {
                                    p.rect_filled(
                                        egui::Rect::from_min_max(
                                            egui::pos2(item.min.x, icon.min.y + 8.0),
                                            egui::pos2(item.min.x + 4.0, icon.max.y - 8.0),
                                        ),
                                        egui::CornerRadius { nw: 0, ne: 2, sw: 0, se: 2 },
                                        pal::WHITE,
                                    );
                                    // White ring around the active round icon
                                    p.circle_filled(icon.center(), 23.0, pal::WHITE);
                                    p.circle_filled(icon.center(), 21.5, pal::TAB_BAR);
                                } else if resp.hovered() {
                                    p.circle_filled(icon.center(), 24.0, pal::TAB_HOVER);
                                }

                                let mut fill = rgb(s.accent_rgb);
                                if !is_active && !resp.hovered() {
                                    fill = fill.gamma_multiply(0.75);
                                }
                                p.circle_filled(icon.center(), 20.0, fill);
                                p.text(
                                    icon.center(),
                                    egui::Align2::CENTER_CENTER,
                                    initials(&tab_title(&s.label)),
                                    egui::FontId::proportional(16.0),
                                    pal::WHITE,
                                );

                                // Service badge (W = WhatsApp, T = Telegram)
                                // Badges sit on the circle's rim at 45°
                                let b = icon.center() + egui::vec2(15.0, 15.0);
                                p.circle_filled(b, 8.5, pal::TAB_BAR);
                                p.circle_filled(b, 7.0, s.service.accent());
                                p.text(
                                    b,
                                    egui::Align2::CENTER_CENTER,
                                    s.service.letter(),
                                    egui::FontId::proportional(9.0),
                                    pal::WHITE,
                                );

                                // Status dot (only when not simply "connected")
                                if s.locked || !s.connected {
                                    let d = icon.center() + egui::vec2(-15.0, 15.0);
                                    p.circle_filled(d, 6.0, pal::TAB_BAR);
                                    p.circle_filled(d, 4.5, status_color(s));
                                }

                                // Unread badge
                                let unread = unread_map.get(&s.id).copied().unwrap_or(0);
                                if unread > 0 {
                                    let txt = if unread > 99 { "99+".to_string() } else { unread.to_string() };
                                    let w = 10.0 + 6.0 * txt.len() as f32;
                                    let pill = egui::Rect::from_center_size(
                                        icon.center() + egui::vec2(16.0, -16.0),
                                        egui::vec2(w.max(18.0), 18.0),
                                    );
                                    p.rect_filled(pill.expand(1.5), 10.0, pal::TAB_BAR);
                                    p.rect_filled(pill, 9.0, pal::GREEN);
                                    p.text(
                                        pill.center(),
                                        egui::Align2::CENTER_CENTER,
                                        txt,
                                        egui::FontId::proportional(10.0),
                                        pal::WHITE,
                                    );
                                }

                                p.text(
                                    egui::pos2(cx, item.max.y - 3.0),
                                    egui::Align2::CENTER_BOTTOM,
                                    short(&tab_title(&s.label), 9),
                                    egui::FontId::proportional(10.0),
                                    if is_active { pal::ON_DARK } else { pal::HINT },
                                );

                                if resp.clicked() {
                                    clicked = Some(idx);
                                }
                                resp.on_hover_text(format!(
                                    "{} · {}\nTel: {}\nPartición aislada: {}\nCookie Jar: {}",
                                    s.service.name(),
                                    s.label,
                                    s.phone_number,
                                    s.partition_path,
                                    s.cookie_jar_id
                                ));
                            }

                            // "+" = add a WhatsApp or Telegram account
                            let (plus_item, plus_resp) = ui.allocate_exact_size(
                                egui::vec2(ACCOUNT_RAIL_WIDTH, 52.0),
                                egui::Sense::click(),
                            );
                            let plus = egui::Rect::from_center_size(
                                plus_item.center(),
                                egui::vec2(42.0, 42.0),
                            );
                            let p = ui.painter();
                            if plus_resp.hovered() {
                                p.circle_filled(plus.center(), 21.0, pal::TAB_INACTIVE);
                            }
                            p.circle_stroke(plus.center(), 20.25, egui::Stroke::new(1.5, pal::ICON));
                            p.text(
                                plus.center(),
                                egui::Align2::CENTER_CENTER,
                                "+",
                                egui::FontId::proportional(22.0),
                                pal::ON_DARK_MUTED,
                            );
                            if plus_resp
                                .on_hover_text("Añadir cuenta de WhatsApp o Telegram")
                                .clicked()
                            {
                                self.show_new_session_modal = true;
                            }
                        });
                });

                // Bottom controls: notifications of the active account + side panel toggle
                ui.scope_builder(egui::UiBuilder::new().max_rect(bottom_rect), |ui| {
                    ui.vertical_centered(|ui| {
                        ui.spacing_mut().item_spacing.y = 2.0;
                        ui.add_space(6.0);
                        let notif_on = self
                            .sessions
                            .get(self.active_session_idx)
                            .map(|s| s.notifications_enabled)
                            .unwrap_or(false);
                        if ui
                            .add(
                                egui::Button::new(
                                    egui::RichText::new(if notif_on { "Avisos: sí" } else { "Avisos: no" })
                                        .size(10.5)
                                        .color(pal::ON_DARK_MUTED),
                                )
                                .frame(false),
                            )
                            .on_hover_text("Notificaciones de la cuenta activa")
                            .clicked()
                        {
                            toggle_notifications = true;
                        }
                        if ui
                            .add(
                                egui::Button::new(
                                    egui::RichText::new(if self.show_info_panel { "Ocultar panel" } else { "Ajustes" })
                                        .size(10.5)
                                        .color(pal::ON_DARK_MUTED),
                                )
                                .frame(false),
                            )
                            .clicked()
                        {
                            self.show_info_panel = !self.show_info_panel;
                        }
                    });
                });

                if let Some(idx) = clicked {
                    self.active_session_idx = idx;
                    save_session_order(&self.storage_root, &self.sessions, idx);
                }
                if toggle_notifications {
                    if let Some(s) = self.sessions.get_mut(self.active_session_idx) {
                        s.notifications_enabled = !s.notifications_enabled;
                    }
                    self.persist_active();
                }
            });

        // --------------------------------------------------------------------
        // 2. Right Session & Isolation Panel (INFO_PANEL_WIDTH = 260)
        // --------------------------------------------------------------------
        if self.show_info_panel {
            egui::Panel::right("session_isolation_inspector")
                .exact_size(INFO_PANEL_WIDTH)
                .resizable(false)
                .frame(
                    egui::Frame::NONE
                        .fill(pal::WHITE)
                        .inner_margin(egui::Margin::symmetric(18, 20)),
                )
                .show(ui, |ui| {
                    let active_idx = self.active_session_idx;
                    let mut should_save = false;
                    let mut switch_to: Option<usize> = None;
                    let mut reload = false;

                    egui::ScrollArea::vertical()
                        .auto_shrink([false, false])
                        .show(ui, |ui| {
                            section_label(ui, "SESIÓN AISLADA");
                            if let Some(session) = self.sessions.get(active_idx) {
                                egui::Frame::NONE
                                    .fill(pal::CARD)
                                    .corner_radius(18.0)
                                    .inner_margin(egui::Margin::same(14))
                                    .show(ui, |ui| {
                                        ui.set_width(ui.available_width());
                                        kv(ui, "Estado", status_text(session), status_color(session));
                                        kv(ui, "Número", &session.phone_number, pal::TEXT);
                                        kv(ui, "Partición", &session.partition_path, pal::TEXT);
                                        kv(ui, "Cookies", &session.cookie_jar_id, pal::TEXT);
                                        kv(ui, "Almacenamiento", &session.storage_namespace, pal::TEXT);
                                        kv(
                                            ui,
                                            "Persistencia",
                                            &format!(
                                                "{} B · guardado {}",
                                                session.persisted_bytes, session.last_saved_label
                                            ),
                                            pal::TEXT,
                                        );
                                    });
                            }

                            ui.add_space(14.0);
                            section_label(ui, "TODAS LAS CUENTAS");
                            for (idx, s) in self.sessions.iter().enumerate() {
                                let unread = unread_map.get(&s.id).copied().unwrap_or(0);
                                let detail = if s.locked || !s.connected {
                                    status_text(s).to_lowercase()
                                } else if unread > 0 {
                                    format!("{} sin leer", unread)
                                } else {
                                    "al día".to_string()
                                };
                                let (rect, resp) = ui.allocate_exact_size(
                                    egui::vec2(ui.available_width(), 26.0),
                                    egui::Sense::click(),
                                );
                                if resp.hovered() || idx == active_idx {
                                    ui.painter().rect_filled(rect, 13.0, pal::GREY);
                                }
                                ui.painter().circle_filled(
                                    egui::pos2(rect.min.x + 10.0, rect.center().y),
                                    5.0,
                                    status_color(s),
                                );
                                ui.painter().text(
                                    egui::pos2(rect.min.x + 22.0, rect.center().y),
                                    egui::Align2::LEFT_CENTER,
                                    format!("{} — {}", tab_title(&s.label), detail),
                                    egui::FontId::proportional(13.0),
                                    pal::TEXT,
                                );
                                if resp.clicked() {
                                    switch_to = Some(idx);
                                }
                            }

                            ui.add_space(14.0);
                            section_label(ui, "AJUSTES DE LA CUENTA");
                            if let Some(session) = self.sessions.get_mut(active_idx) {
                                field_label(ui, "Nombre de la cuenta");
                                should_save |= ui.text_edit_singleline(&mut session.label).changed();
                                field_label(ui, &format!("Número de {}", session.service.name()));
                                should_save |=
                                    ui.text_edit_singleline(&mut session.phone_number).changed();
                                field_label(ui, "Red / contenedor aislado");
                                should_save |=
                                    ui.text_edit_singleline(&mut session.isolated_proxy).changed();
                                ui.add_space(6.0);
                                should_save |= ui
                                    .checkbox(
                                        &mut session.notifications_enabled,
                                        "Notificaciones de esta sesión",
                                    )
                                    .changed();
                                field_label(ui, "Notas privadas");
                                should_save |= ui
                                    .add(
                                        egui::TextEdit::multiline(&mut session.session_notes)
                                            .desired_rows(3),
                                    )
                                    .changed();
                                ui.add_space(6.0);
                                if ui
                                    .button("Rotar CookieJar ID")
                                    .on_hover_text(
                                        "Genera un identificador de cookies nuevo para esta sesión",
                                    )
                                    .clicked()
                                {
                                    session.cookie_jar_id = format!(
                                        "jar_iso_{}_{}",
                                        session.id,
                                        current_hhmmss().replace(':', "")
                                    );
                                    should_save = true;
                                }


                            }

                            ui.add_space(16.0);
                            // SPLASH_DELAY_MS (1000 ms, 0x402edd) gates the "persisted" badge
                            let synced = self.start_instant.elapsed().as_millis() as u64
                                >= SPLASH_DELAY_MS
                                && self.sync_progress_pct >= 100;
                            let footer = if synced {
                                format!("Persistido · {}", self.status_toast)
                            } else {
                                format!("Sincronizando… {}%", self.sync_progress_pct)
                            };
                            ui.label(egui::RichText::new(footer).size(10.5).color(pal::MUTED));
                            ui.add_space(8.0);

                            if wide_button(ui, "Recargar", pal::ACCENT_DARK, pal::WHITE)
                                .on_hover_text("Recarga la página web de esta cuenta")
                                .clicked()
                            {
                                reload = true;
                            }
                            ui.add_space(8.0);
                            if let Some(session) = self.sessions.get_mut(active_idx) {
                                let (label, fg, bg) = if session.connected {
                                    (
                                        format!("Desconectar {}", tab_title(&session.label)),
                                        pal::DANGER,
                                        pal::DANGER_BG,
                                    )
                                } else {
                                    ("Conectar sesión".to_string(), pal::WHITE, session.service.accent())
                                };
                                // Disconnect closes the web view; its login data stays on disk
                                if wide_button(ui, &label, fg, bg).clicked() {
                                    session.connected = !session.connected;
                                    should_save = true;
                                }
                            }
                        });

                    if let Some(idx) = switch_to {
                        self.active_session_idx = idx;
                        save_session_order(&self.storage_root, &self.sessions, idx);
                    }
                    if reload {
                        if let Some(id) = self.sessions.get(active_idx).map(|s| s.id.clone()) {
                            // A failed view is retried; a live one reloads its page
                            self.web_errors.remove(&id);
                            if let Some(acc) = self.webviews.get(&id) {
                                let _ = acc.view.reload();
                            }
                        }
                    }
                    if should_save {
                        self.persist_active();
                    }
                });
        }

        // --------------------------------------------------------------------
        // 3. Center: the real WhatsApp Web / Telegram Web of the active account.
        //    egui only reserves the area; the native webview is placed over it
        //    in `sync_webviews` after the frame's UI is known.
        // --------------------------------------------------------------------
        let svc = self
            .sessions
            .get(self.active_session_idx)
            .map(|s| s.service)
            .unwrap_or(Service::WhatsApp);
        let web_area = egui::CentralPanel::default()
            .frame(egui::Frame::NONE.fill(svc.chat_bg()))
            .show(ui, |ui| {
                let mut toggle_lock = false;
                let mut connect = false;
                let mut area = None;
                if let Some(session) = self.sessions.get(self.active_session_idx) {
                    let error = self.web_errors.get(&session.id).cloned();
                    let (title, detail, action) = if session.locked {
                        (
                            format!("{} está bloqueada", tab_title(&session.label)),
                            "La conversación queda oculta hasta que la desbloquees.".to_string(),
                            Some("Desbloquear"),
                        )
                    } else if !session.connected {
                        (
                            format!("{} está desconectada", tab_title(&session.label)),
                            format!(
                                "Sus datos siguen guardados en {}. Conéctala para abrir {} Web.",
                                session.partition_path,
                                session.service.name()
                            ),
                            Some("Conectar"),
                        )
                    } else if let Some(err) = error {
                        (
                            format!("No se pudo abrir {} Web", session.service.name()),
                            err,
                            None,
                        )
                    } else {
                        area = Some(ui.max_rect());
                        (String::new(), String::new(), None)
                    };

                    if area.is_none() {
                        ui.vertical_centered(|ui| {
                            ui.add_space(ui.available_height() * 0.35);
                            ui.label(egui::RichText::new(title).size(20.0).strong().color(pal::TEXT));
                            ui.add_space(6.0);
                            ui.label(egui::RichText::new(detail).size(13.0).color(pal::MUTED));
                            ui.add_space(16.0);
                            if let Some(label) = action {
                                ui.spacing_mut().button_padding = egui::vec2(22.0, 12.0);
                                if pill(ui, label, pal::WHITE, session.service.accent()).clicked() {
                                    if session.locked {
                                        toggle_lock = true;
                                    } else {
                                        connect = true;
                                    }
                                }
                            }
                        });
                    }
                }
                if toggle_lock || connect {
                    if let Some(s) = self.sessions.get_mut(self.active_session_idx) {
                        if toggle_lock {
                            s.locked = false;
                        }
                        if connect {
                            s.connected = true;
                        }
                    }
                    self.persist_active();
                }
                area
            })
            .inner;

        // --------------------------------------------------------------------
        // 6. Modal: Add an isolated & persistent WhatsApp or Telegram account
        // --------------------------------------------------------------------
        if self.show_new_session_modal {
            egui::Window::new("Añadir cuenta")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(&ctx, |ui| {
                    ui.set_width(404.0);
                    ui.label(
                        egui::RichText::new(
                            "Cada cuenta corre en su propio contenedor, con cookies y datos separados en disco.",
                        )
                        .size(12.5)
                        .color(pal::MUTED),
                    );
                    ui.add_space(12.0);
                    section_label(ui, "SERVICIO");

                    // Service cards with round icons
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 12.0;
                        for svc in Service::ALL {
                            let selected = self.new_session_service == svc;
                            let (card, resp) = ui.allocate_exact_size(
                                egui::vec2(196.0, 72.0),
                                egui::Sense::click(),
                            );
                            let p = ui.painter();
                            let (bg, border) = if selected {
                                (svc.accent().gamma_multiply(0.08), svc.accent())
                            } else if resp.hovered() {
                                (pal::HOVER, pal::BORDER)
                            } else {
                                (pal::WHITE, pal::BORDER)
                            };
                            p.rect_filled(card, 18.0, pal::WHITE);
                            p.rect_filled(card, 18.0, bg);
                            p.rect_stroke(card, 18.0, egui::Stroke::new(2.0, border), egui::StrokeKind::Inside);
                            let ic = egui::pos2(card.min.x + 36.0, card.center().y);
                            p.circle_filled(ic, 22.0, svc.accent());
                            p.text(ic, egui::Align2::CENTER_CENTER, svc.letter(), egui::FontId::proportional(18.0), pal::WHITE);
                            p.text(
                                egui::pos2(card.min.x + 68.0, card.center().y - 9.0),
                                egui::Align2::LEFT_CENTER,
                                svc.name(),
                                egui::FontId::proportional(14.0),
                                pal::TEXT,
                            );
                            p.text(
                                egui::pos2(card.min.x + 68.0, card.center().y + 10.0),
                                egui::Align2::LEFT_CENTER,
                                match svc {
                                    Service::WhatsApp => "Vía código QR",
                                    Service::Telegram => "Vía código",
                                },
                                egui::FontId::proportional(11.5),
                                pal::MUTED,
                            );
                            let rc = egui::pos2(card.max.x - 24.0, card.center().y);
                            if selected {
                                p.circle_filled(rc, 10.0, svc.accent());
                                p.line_segment(
                                    [rc + egui::vec2(-4.5, 0.0), rc + egui::vec2(-1.0, 3.5)],
                                    egui::Stroke::new(2.0, pal::WHITE),
                                );
                                p.line_segment(
                                    [rc + egui::vec2(-1.0, 3.5), rc + egui::vec2(5.0, -3.5)],
                                    egui::Stroke::new(2.0, pal::WHITE),
                                );
                            } else {
                                p.circle_stroke(rc, 9.0, egui::Stroke::new(2.0, pal::BORDER));
                            }
                            if resp.clicked() {
                                self.new_session_service = svc;
                            }
                        }
                    });

                    // Pill text fields
                    let pill_field = |ui: &mut egui::Ui, label: &str, value: &mut String, hint: &str| {
                        ui.add_space(10.0);
                        ui.label(egui::RichText::new(label).size(12.0).color(pal::ICON));
                        ui.add_space(4.0);
                        ui.add_sized(
                            [ui.available_width(), 44.0],
                            egui::TextEdit::singleline(value)
                                .hint_text(hint)
                                .background_color(pal::GREY)
                                .margin(egui::Margin::symmetric(18, 13)),
                        );
                    };
                    pill_field(ui, "Nombre de la cuenta", &mut self.new_session_name, "ej. Ventas");
                    pill_field(ui, "Número de teléfono", &mut self.new_session_phone, "+34 600 00 00 00");
                    pill_field(ui, "Red / contenedor", &mut self.new_session_proxy, "Contenedor aislado directo");
                    ui.add_space(18.0);

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.spacing_mut().item_spacing.x = 10.0;
                        ui.spacing_mut().button_padding = egui::vec2(20.0, 12.0);
                        let svc = self.new_session_service;
                        if pill(ui, &format!("+  Añadir cuenta de {}", svc.name()), pal::WHITE, svc.accent())
                            .clicked()
                            && !self.new_session_name.trim().is_empty()
                        {
                            // Never reuse an id or an existing folder on disk (would overwrite its data)
                            let mut idx_num = self.sessions.len() + 1;
                            let mut slug = format!("{}_sesion_{}", svc.slug(), idx_num);
                            while self.sessions.iter().any(|s| s.id == slug)
                                || self.storage_root.join(&slug).exists()
                            {
                                idx_num += 1;
                                slug = format!("{}_sesion_{}", svc.slug(), idx_num);
                            }
                            let phone = if self.new_session_phone.trim().is_empty() {
                                format!("+34 600 00 00 {:02}", idx_num)
                            } else {
                                self.new_session_phone.trim().to_string()
                            };
                            let mut new_sess = IsolatedSession {
                                id: slug.clone(),
                                service: svc,
                                label: self.new_session_name.trim().to_string(),
                                badge_short: format!("{}-{}", svc.slug().to_uppercase(), idx_num),
                                phone_number: phone.clone(),
                                partition_path: format!("sessions_data/{}", slug),
                                cookie_jar_id: format!("jar_iso_{}_{:02}", slug, idx_num),
                                storage_namespace: format!("ns_local_{}", slug),
                                accent_rgb: svc.accent_rgb(),
                                connected: true,
                                locked: false,
                                notifications_enabled: true,
                                isolated_proxy: self.new_session_proxy.trim().to_string(),
                                session_notes: format!("Cuenta de {} aislada creada por el usuario.", svc.name()),
                                last_saved_label: current_hhmmss(),
                                persisted_bytes: 0,
                                selected_chat_idx: 0,
                                chats: vec![WhatsAppChat {
                                    id: format!("chat_init_{}", idx_num),
                                    contact_name: match svc {
                                        Service::WhatsApp => "Notas de la sesión",
                                        Service::Telegram => "Mensajes guardados",
                                    }
                                    .to_string(),
                                    phone,
                                    status_text: "Contenedor aislado verificado".to_string(),
                                    unread_count: 0,
                                    favorite: true,
                                    archived: false,
                                    muted: false,
                                    avatar_rgb: svc.accent_rgb(),
                                    draft: String::new(),
                                    messages: vec![ChatMessage {
                                        id: 1,
                                        sender: "Sistema".to_string(),
                                        outgoing: false,
                                        text: format!(
                                            "Nueva cuenta de {} aislada y persistente inicializada en disco.",
                                            svc.name()
                                        ),
                                        timestamp: current_hhmm(),
                                        read: true,
                                        starred: true,
                                        reaction: "[OK]".to_string(),
                                        attachment_kind: String::new(),
                                        reply_to: String::new(),
                                    }],
                                }],
                            };
                            let _ = save_isolated_session(&self.storage_root, &mut new_sess);
                            self.sessions.push(new_sess);
                            self.active_session_idx = self.sessions.len() - 1;
                            save_session_order(
                                &self.storage_root,
                                &self.sessions,
                                self.active_session_idx,
                            );
                            self.new_session_name.clear();
                            self.new_session_phone.clear();
                            self.show_new_session_modal = false;
                        }
                        if pill(ui, "Cancelar", pal::ICON, pal::GREY).clicked() {
                            self.show_new_session_modal = false;
                        }
                    });
                });
        }

        // Place / show / hide the native web views now that the layout is known
        self.sync_webviews(frame, &ctx, web_area);

        // --------------------------------------------------------------------
        // 8. Screenshot Mode Handling (~120 frames, background-safe)
        // --------------------------------------------------------------------
        if let Some(ref target_path) = self.screenshot_target {
            self.frame_counter = self.frame_counter.saturating_add(1);
            if self.frame_counter >= 120 && !self.screenshot_requested {
                self.screenshot_requested = true;
                let save_path = target_path.clone();
                let ctx_clone = ctx.clone();
                ctx.request_screenshot(move |image| {
                    if let Some(parent) = save_path.parent() {
                        let _ = fs::create_dir_all(parent);
                    }
                    let _ = image::save_buffer(
                        &save_path,
                        image.as_raw(),
                        image.width() as u32,
                        image.height() as u32,
                        image::ColorType::Rgba8,
                    );
                    ctx_clone.send_viewport_cmd(egui::ViewportCommand::Close);
                    std::process::exit(0);
                });
            }
            ctx.send_viewport_cmd(egui::ViewportCommand::RequestPaintWhileHidden);
            ctx.request_repaint();
        }
    }
}

fn parse_screenshot_arg() -> Option<PathBuf> {
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        if args[i] == "--screenshot" {
            if let Some(p) = args.get(i + 1) {
                return Some(PathBuf::from(p));
            }
        } else if let Some(rest) = args[i].strip_prefix("--screenshot=") {
            return Some(PathBuf::from(rest));
        }
        i += 1;
    }
    None
}

fn main() -> eframe::Result<()> {
    let screenshot_target = parse_screenshot_arg();

    // wgpu (not OpenGL) so egui composes correctly next to the WebView2 child windows.
    // DX12: the Vulkan path crashes on this gnullvm build. An explicit WGPU_BACKEND still wins.
    #[cfg(target_os = "windows")]
    if std::env::var_os("WGPU_BACKEND").is_none() {
        std::env::set_var("WGPU_BACKEND", "dx12");
    }

    let mut viewport = egui::ViewportBuilder::default()
        .with_title("Multicuenta • WhatsApp y Telegram")
        .with_inner_size([WINDOW_WIDTH, WINDOW_HEIGHT])
        .with_min_inner_size([860.0, 540.0]);

    if screenshot_target.is_some() {
        viewport = viewport.with_active(false);
        #[cfg(target_os = "windows")]
        {
            viewport = viewport.with_window_level(egui::WindowLevel::AlwaysOnBottom);
        }
    }

    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };

    eframe::run_native(
        "Multicuenta • WhatsApp y Telegram",
        options,
        Box::new(move |_cc| Ok(Box::new(WhatsAppMultiSessionApp::new(screenshot_target)))),
    )
}
