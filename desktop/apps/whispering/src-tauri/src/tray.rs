//! VoiceFlow: the tray icon and its native menu (plan v2, phase A1).
//!
//! Built in Rust so the tray outlives anything the main webview does. Left
//! click opens the main window; the menu shows the dictation status, Open
//! VoiceFlow, Theme, Start with Windows / Open at login, and Quit VoiceFlow.
//!
//! The webview reports state to it (`tray_set_recording`, `tray_set_theme`)
//! and applies a Theme picked here (event `tray://theme`), because the theme
//! setting lives in the webview. Toggling the login item here emits
//! `autostart://changed` so Settings can refresh.

use std::sync::Mutex;

use tauri::image::Image;
use tauri::menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu};
use tauri::tray::{MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, Wry};
use tauri_plugin_autostart::ManagerExt;

use crate::lifecycle::{show_main, MAIN_WINDOW};

const TRAY_ID: &str = "voiceflow-tray";

const ID_STATUS: &str = "status";
const ID_OPEN: &str = "open";
const ID_AUTOSTART: &str = "autostart";
const ID_QUIT: &str = "quit";
const THEME_PREFIX: &str = "theme-";

/// The same choices as `THEME_OPTIONS` in `src/lib/theme.svelte.ts`.
const THEMES: [(&str, &str); 3] = [("system", "System"), ("light", "Light"), ("dark", "Dark")];

pub const EVENT_THEME: &str = "tray://theme";
pub const EVENT_AUTOSTART_CHANGED: &str = "autostart://changed";

#[cfg(target_os = "windows")]
const AUTOSTART_LABEL: &str = "Start with Windows";
#[cfg(target_os = "macos")]
const AUTOSTART_LABEL: &str = "Open at login";
#[cfg(not(any(target_os = "windows", target_os = "macos")))]
const AUTOSTART_LABEL: &str = "Start at login";

const ICON_IDLE: &[u8] = include_bytes!("../icons/tray/idle.png");
const ICON_RECORDING: &[u8] = include_bytes!("../icons/tray/recording.png");

/// What the webview last reported. Managed from the start (`.manage` on the
/// builder), so a report that arrives before the tray exists is not lost.
pub struct TrayReport {
    theme: Mutex<String>,
    recording: Mutex<bool>,
    /// VoiceFlow: the main webview has not answered a hotkey within a second
    /// (`hotkey.rs`); the status says so until it does.
    waking: Mutex<bool>,
}

impl Default for TrayReport {
    fn default() -> Self {
        Self {
            theme: Mutex::new("system".to_string()),
            recording: Mutex::new(false),
            waking: Mutex::new(false),
        }
    }
}

/// Handles to the tray and the menu items whose state changes after start.
pub struct TrayState {
    tray: TrayIcon<Wry>,
    status: MenuItem<Wry>,
    autostart: CheckMenuItem<Wry>,
    themes: Vec<(&'static str, CheckMenuItem<Wry>)>,
}

impl TrayState {
    fn check_theme(&self, choice: &str) {
        for (value, item) in &self.themes {
            let _ = item.set_checked(*value == choice);
        }
    }

    fn show_recording(&self, recording: bool, waking: bool) -> tauri::Result<()> {
        let (bytes, status, tooltip) = match (recording, waking) {
            (true, true) => (ICON_RECORDING, "Recording… (VoiceFlow is waking up…)", "VoiceFlow: recording, waking up…"),
            (true, false) => (ICON_RECORDING, "Recording…", "VoiceFlow: recording"),
            (false, true) => (ICON_IDLE, "VoiceFlow is waking up…", "VoiceFlow is waking up…"),
            (false, false) => (ICON_IDLE, "Ready", "VoiceFlow"),
        };
        self.tray.set_icon(Some(Image::from_bytes(bytes)?))?;
        self.tray.set_tooltip(Some(tooltip))?;
        self.status.set_text(status)?;
        Ok(())
    }
}

fn reported_theme(app: &AppHandle<Wry>) -> String {
    app.try_state::<TrayReport>()
        .and_then(|r| r.theme.lock().ok().map(|t| t.clone()))
        .unwrap_or_else(|| "system".to_string())
}

fn reported_recording(app: &AppHandle<Wry>) -> bool {
    app.try_state::<TrayReport>()
        .and_then(|r| r.recording.lock().ok().map(|v| *v))
        .unwrap_or(false)
}

fn reported_waking(app: &AppHandle<Wry>) -> bool {
    app.try_state::<TrayReport>()
        .and_then(|r| r.waking.lock().ok().map(|v| *v))
        .unwrap_or(false)
}

/// Updates the icon and status from what was last reported.
fn refresh_status(app: &AppHandle<Wry>) -> tauri::Result<()> {
    if let Some(state) = app.try_state::<TrayState>() {
        state.show_recording(reported_recording(app), reported_waking(app))?;
    }
    Ok(())
}

/// VoiceFlow: the native hotkey worker reports a recording it started or
/// stopped, so the tray shows it even while the webview sleeps.
pub fn show_recording(app: &AppHandle<Wry>, recording: bool) {
    if let Some(report) = app.try_state::<TrayReport>() {
        if let Ok(mut current) = report.recording.lock() {
            *current = recording;
        }
    }
    let _ = refresh_status(app);
}

/// VoiceFlow: "VoiceFlow is waking up…" while a hotkey waits for the webview.
pub fn show_waking(app: &AppHandle<Wry>, waking: bool) {
    if let Some(report) = app.try_state::<TrayReport>() {
        if let Ok(mut current) = report.waking.lock() {
            *current = waking;
        }
    }
    let _ = refresh_status(app);
}

/// Creates the tray icon and menu and puts their handles in app state.
pub fn create(app: &AppHandle<Wry>) -> tauri::Result<()> {
    let status = MenuItem::with_id(app, ID_STATUS, "Ready", false, None::<&str>)?;
    let open = MenuItem::with_id(app, ID_OPEN, "Open VoiceFlow", true, None::<&str>)?;

    let mut themes = Vec::with_capacity(THEMES.len());
    for (value, label) in THEMES {
        let item = CheckMenuItem::with_id(
            app,
            format!("{THEME_PREFIX}{value}"),
            label,
            true,
            value == "system",
            None::<&str>,
        )?;
        themes.push((value, item));
    }
    let theme_refs: Vec<&dyn tauri::menu::IsMenuItem<Wry>> = themes
        .iter()
        .map(|(_, item)| item as &dyn tauri::menu::IsMenuItem<Wry>)
        .collect();
    let theme_menu = Submenu::with_items(app, "Theme", true, &theme_refs)?;

    let autostart_on = app.autolaunch().is_enabled().unwrap_or(false);
    let autostart = CheckMenuItem::with_id(
        app,
        ID_AUTOSTART,
        AUTOSTART_LABEL,
        true,
        autostart_on,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, ID_QUIT, "Quit VoiceFlow", true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &status,
            &PredefinedMenuItem::separator(app)?,
            &open,
            &theme_menu,
            &autostart,
            &PredefinedMenuItem::separator(app)?,
            &quit,
        ],
    )?;

    let tray = TrayIconBuilder::with_id(TRAY_ID)
        .icon(Image::from_bytes(ICON_IDLE)?)
        .tooltip("VoiceFlow")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(on_menu_event)
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;

    app.manage(TrayState {
        tray,
        status,
        autostart,
        themes,
    });
    // Apply anything the webview reported before the tray existed.
    if let Some(state) = app.try_state::<TrayState>() {
        state.check_theme(&reported_theme(app));
        state.show_recording(reported_recording(app), reported_waking(app))?;
    }
    Ok(())
}

fn on_menu_event(app: &AppHandle<Wry>, event: MenuEvent) {
    let id = event.id().as_ref();
    match id {
        ID_OPEN => show_main(app),
        ID_QUIT => app.exit(0),
        ID_AUTOSTART => {
            let want = !app.autolaunch().is_enabled().unwrap_or(false);
            if let Err(e) = set_autostart_inner(app, want) {
                log::warn!("[tray] could not change the login item: {e}");
            }
            let _ = app.emit(EVENT_AUTOSTART_CHANGED, ());
        }
        _ => {
            if let Some(choice) = id.strip_prefix(THEME_PREFIX) {
                if let Some(state) = app.try_state::<TrayState>() {
                    state.check_theme(choice);
                }
                // The webview applies it and reports it back (`tray_set_theme`).
                let _ = app.emit_to(MAIN_WINDOW, EVENT_THEME, choice.to_string());
            }
        }
    }
}

/// Turns the login item on or off and makes the tray tick match the result.
fn set_autostart_inner(app: &AppHandle<Wry>, enabled: bool) -> Result<bool, String> {
    let manager = app.autolaunch();
    let result = if enabled {
        manager.enable()
    } else {
        manager.disable()
    };
    let now = manager.is_enabled().unwrap_or(false);
    if let Some(state) = app.try_state::<TrayState>() {
        let _ = state.autostart.set_checked(now);
    }
    result.map_err(|e| e.to_string())?;
    Ok(now)
}

/// Rewrites an existing login item so it carries `--hidden`. Items made by
/// earlier versions launched with the window showing.
pub fn refresh_autostart_args(app: &AppHandle<Wry>) {
    let manager = app.autolaunch();
    if manager.is_enabled().unwrap_or(false) {
        if let Err(e) = manager.enable() {
            log::warn!("[tray] could not refresh the login item: {e}");
        }
    }
}

/// The webview reports whether a recording is in progress.
#[tauri::command]
pub fn tray_set_recording(
    app: AppHandle<Wry>,
    report: State<'_, TrayReport>,
    recording: bool,
) -> Result<(), String> {
    let mut current = report.recording.lock().map_err(|e| e.to_string())?;
    if *current == recording {
        return Ok(());
    }
    *current = recording;
    drop(current);
    refresh_status(&app).map_err(|e| e.to_string())
}

/// The webview reports the current theme so the Theme submenu ticks it.
#[tauri::command]
pub fn tray_set_theme(
    app: AppHandle<Wry>,
    report: State<'_, TrayReport>,
    choice: String,
) -> Result<(), String> {
    if let Some(state) = app.try_state::<TrayState>() {
        state.check_theme(&choice);
    }
    *report.theme.lock().map_err(|e| e.to_string())? = choice;
    Ok(())
}

/// Whether VoiceFlow starts at login (Settings > General).
#[tauri::command]
pub fn autostart_is_enabled(app: AppHandle<Wry>) -> Result<bool, String> {
    app.autolaunch().is_enabled().map_err(|e| e.to_string())
}

/// Turns starting at login on or off (Settings > General); keeps the tray in step.
#[tauri::command]
pub fn autostart_set(app: AppHandle<Wry>, enabled: bool) -> Result<bool, String> {
    set_autostart_inner(&app, enabled)
}
