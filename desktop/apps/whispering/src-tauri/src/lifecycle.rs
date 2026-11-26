//! VoiceFlow: the main window's lifecycle (plan v2, phase A1).
//!
//! - Closing the main window hides it; VoiceFlow keeps running in the tray
//!   (menu bar on macOS) so global shortcuts keep working. Quit lives in the
//!   tray menu.
//! - The first close shows a one-time OS notification saying so.
//! - Started with `--hidden` (autostart at login), the main window stays
//!   hidden; otherwise it is shown once the app is set up.
//! - A second launch shows and focuses the running window.

use tauri::{AppHandle, Manager, Runtime, WebviewWindowBuilder, Window, WindowEvent};

/// The label of the main window (the default label in `tauri.conf.json`).
pub const MAIN_WINDOW: &str = "main";

/// Passed by the login item so VoiceFlow starts in the tray only.
pub const HIDDEN_ARG: &str = "--hidden";

/// Marker file (in the app data folder) recording that the close notice was shown.
const CLOSE_NOTICE_MARKER: &str = "close-to-tray-notice-shown";

#[cfg(target_os = "macos")]
const CLOSE_NOTICE: &str = "VoiceLnq is still running in the menu bar. Quit from its icon.";
#[cfg(not(target_os = "macos"))]
const CLOSE_NOTICE: &str = "VoiceLnq is still running in the taskbar corner. Quit from its icon.";

/// True when the command line asks VoiceFlow to start hidden.
pub fn launched_hidden<I, S>(args: I) -> bool
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    args.into_iter().any(|arg| arg.as_ref() == HIDDEN_ARG)
}

/// VoiceFlow: builds the main window from its `tauri.conf.json` entry
/// (`create: false` there), with the keep-awake WebView2 switches
/// (`webview_args.rs`). Called first thing in `setup`.
pub fn create_main<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    if app.get_webview_window(MAIN_WINDOW).is_some() {
        return Ok(());
    }
    let config = app
        .config()
        .app
        .windows
        .iter()
        .find(|w| w.label == MAIN_WINDOW)
        .cloned()
        .unwrap_or_default();
    let builder = WebviewWindowBuilder::from_config(app, &config)?;
    crate::webview_args::keep_awake(app, builder).build()?;
    Ok(())
}

/// Shows, restores and focuses the main window. Never panics.
pub fn show_main<R: Runtime>(app: &AppHandle<R>) {
    let Some(window) = app.get_webview_window(MAIN_WINDOW) else {
        log::warn!("[lifecycle] no main window to show");
        return;
    };
    let _ = window.unminimize();
    let _ = window.show();
    let _ = window.set_focus();
}

/// `Builder::on_window_event` handler: closing the main window hides it.
pub fn on_window_event<R: Runtime>(window: &Window<R>, event: &WindowEvent) {
    if let WindowEvent::CloseRequested { api, .. } = event {
        if window.label() != MAIN_WINDOW {
            return;
        }
        api.prevent_close();
        if let Err(e) = window.hide() {
            log::warn!("[lifecycle] could not hide the main window: {e}");
        }
        show_close_notice_once(window.app_handle());
    }
}

/// Shows the "still running" notification the first time the window is closed.
/// A toast does not take focus.
fn show_close_notice_once<R: Runtime>(app: &AppHandle<R>) {
    use tauri_plugin_notification::NotificationExt;

    let Ok(dir) = app.path().app_data_dir() else {
        return;
    };
    let marker = dir.join(CLOSE_NOTICE_MARKER);
    if marker.exists() {
        return;
    }
    match app
        .notification()
        .builder()
        .title("VoiceLnq")
        .body(CLOSE_NOTICE)
        .show()
    {
        Ok(()) => {
            let _ = std::fs::create_dir_all(&dir);
            if let Err(e) = std::fs::write(&marker, b"1") {
                log::warn!("[lifecycle] could not record the close notice: {e}");
            }
        }
        Err(e) => log::warn!("[lifecycle] could not show the close notice: {e}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hidden_arg_is_detected() {
        assert!(launched_hidden(["voiceflow.exe", "--hidden"]));
        assert!(!launched_hidden(["voiceflow.exe"]));
        assert!(!launched_hidden(["voiceflow.exe", "--hiddenx"]));
    }
}
