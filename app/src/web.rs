//! Real WhatsApp Web / Telegram Web embedding.
//!
//! Each account gets its own WebView2 profile folder (`sessions_data/<id>/webview`),
//! so cookies, local storage and login tokens never mix between accounts — the same
//! isolation Franz/Rambox get from separate browser partitions.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{mpsc, Arc, Mutex};

use eframe::egui;

use crate::Service;

/// Android's emoji font (Noto Color Emoji, COLRv1, SIL OFL 1.1 — see assets/Noto-Emoji-LICENSE.txt).
const EMOJI_FONT: &[u8] = include_bytes!("../assets/Noto-COLRv1.ttf");

/// Tiny init script: the top frame asks the app for the emoji font once its DOM exists.
/// The 6.7 MB font payload is only sent on request, not injected into every frame.
const REQUEST_FONT_JS: &str = r#"
if (window.top === window) {
  document.addEventListener('DOMContentLoaded', () => window.ipc.postMessage('emoji-font'));
}"#;

pub struct AccountView {
    /// Keeps the account's isolated profile folder alive as long as the view.
    _context: wry::WebContext,
    pub view: wry::WebView,
    pub visible: bool,
    pub bounds: Option<egui::Rect>,
}

pub fn web_url(service: Service) -> &'static str {
    match service {
        Service::WhatsApp => "https://web.whatsapp.com/",
        Service::Telegram => "https://web.telegram.org/k/",
    }
}

/// Logical egui rect -> physical wry rect.
pub fn to_wry_rect(r: egui::Rect, pixels_per_point: f32) -> wry::Rect {
    wry::Rect {
        position: wry::dpi::PhysicalPosition::new(
            (r.min.x * pixels_per_point).round() as i32,
            (r.min.y * pixels_per_point).round() as i32,
        )
        .into(),
        size: wry::dpi::PhysicalSize::new(
            (r.width() * pixels_per_point).round().max(1.0) as u32,
            (r.height() * pixels_per_point).round().max(1.0) as u32,
        )
        .into(),
    }
}

#[allow(clippy::too_many_arguments)]
pub fn create(
    window: &eframe::Frame,
    service: Service,
    data_dir: PathBuf,
    id: &str,
    bounds: wry::Rect,
    ctx: &egui::Context,
    unread: Arc<Mutex<HashMap<String, usize>>>,
    font_tx: mpsc::Sender<String>,
) -> wry::Result<AccountView> {
    let _ = std::fs::create_dir_all(&data_dir);
    let mut context = wry::WebContext::new(Some(data_dir));
    let (id_title, ctx_title) = (id.to_string(), ctx.clone());
    let (id_font, ctx_font) = (id.to_string(), ctx.clone());
    let view = wry::WebViewBuilder::new_with_web_context(&mut context)
        .with_url(web_url(service))
        .with_bounds(bounds)
        .with_initialization_script(REQUEST_FONT_JS)
        .with_ipc_handler(move |req| {
            if req.body() == "emoji-font" {
                let _ = font_tx.send(id_font.clone());
                ctx_font.request_repaint();
            }
        })
        // "(3) WhatsApp" / "Telegram (3)" -> unread badge on the rail
        .with_document_title_changed_handler(move |title| {
            if let Ok(mut map) = unread.lock() {
                map.insert(id_title.clone(), parse_unread(&title));
            }
            ctx_title.request_repaint();
        })
        // Links that open a new window go to the system browser, not a stray popup
        .with_new_window_req_handler(|url, _| {
            open_external(&url);
            wry::NewWindowResponse::Deny
        })
        .build_as_child(window)?;
    Ok(AccountView {
        _context: context,
        view,
        visible: true,
        bounds: None,
    })
}

/// Script that registers Noto Color Emoji under the usual emoji font names, so text
/// emoji drawn with a font (inputs, names, sites using native emoji) look like Android.
/// Sites that draw emoji as images (WhatsApp Web) keep their own images.
pub fn emoji_font_script() -> String {
    format!(
        r#"(function(b){{
  if (window.__notoEmoji) return; window.__notoEmoji = true;
  const bin = Uint8Array.from(atob(b), c => c.charCodeAt(0)).buffer;
  for (const fam of ['Noto Color Emoji','Segoe UI Emoji','Segoe UI Symbol','Apple Color Emoji','Android Emoji','Twemoji Mozilla','EmojiOne Color']) {{
    new FontFace(fam, bin).load().then(f => document.fonts.add(f)).catch(() => {{}});
  }}
}})("{}");"#,
        base64(EMOJI_FONT)
    )
}

/// First "(N)" in a page title, e.g. "(3) WhatsApp" -> 3; no number -> 0.
pub fn parse_unread(title: &str) -> usize {
    title
        .split('(')
        .skip(1)
        .find_map(|part| part.split(')').next()?.trim().parse().ok())
        .unwrap_or(0)
}

/// Opens http(s) links in the user's default browser; anything else is ignored.
fn open_external(url: &str) {
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return;
    }
    #[cfg(target_os = "windows")]
    let _ = std::process::Command::new("rundll32.exe")
        .args(["url.dll,FileProtocolHandler", url])
        .spawn();
    #[cfg(not(target_os = "windows"))]
    let _ = std::process::Command::new("xdg-open").arg(url).spawn();
}

fn base64(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for c in data.chunks(3) {
        let n = (c[0] as u32) << 16
            | (*c.get(1).unwrap_or(&0) as u32) << 8
            | *c.get(2).unwrap_or(&0) as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if c.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if c.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unread_from_titles() {
        assert_eq!(parse_unread("(3) WhatsApp"), 3);
        assert_eq!(parse_unread("Telegram (12)"), 12);
        assert_eq!(parse_unread("WhatsApp"), 0);
        assert_eq!(parse_unread("Chat (beta) (7)"), 7);
    }

    #[test]
    fn base64_matches_rfc4648() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }
}
