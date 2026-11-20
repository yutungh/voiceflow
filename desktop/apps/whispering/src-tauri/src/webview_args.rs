//! VoiceFlow: keep the webviews awake (plan v2, "A-gate").
//!
//! The dictation engine runs in the main webview. WebView2 (Chromium) slows
//! down or stops a page whose window is minimized, hidden or covered:
//! timers are throttled, the renderer is deprioritised, and occluded windows
//! are backgrounded. A hotkey pressed then could wait for minutes or never be
//! handled. Every VoiceFlow webview is created with the switches that turn
//! that off, merged with the arguments wry passes by default (passing any
//! arguments replaces wry's, see `wry::webview2` `additional_browser_args`).
//!
//! All webviews in one process share one WebView2 environment, which only
//! accepts webviews created with identical arguments, so every window
//! (main, recording bar, feedback, transform clipboard) is built through
//! [`keep_awake`].
//!
//! macOS: WKWebView's own suspension of hidden views is turned off with
//! `background_throttling(Disabled)` (macOS 14+; older versions ignore it).

use tauri::utils::config::BackgroundThrottlingPolicy;
use tauri::{Manager, Runtime, WebviewWindowBuilder};

/// What wry passes when no arguments are given (wry 0.53, `webview2/mod.rs`):
/// no mini menu, no PDF out-of-process UI, no SmartScreen, and autoplay
/// without a gesture (Tauri's `autoplay` default, the recording sounds).
pub const WRY_DEFAULT_ARGS: &str =
    "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required";

/// Never background the dictation engine.
pub const KEEP_AWAKE_ARGS: &str =
    "--disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows";

/// Test hook (development identifier only): the webviews are created with
/// wry's defaults alone, to compare against.
const PLAIN_ENV: &str = "VOICEFLOW_DEV_PLAIN_WEBVIEW";

/// The arguments every VoiceFlow webview is created with.
pub fn browser_args(plain: bool) -> String {
    if plain {
        WRY_DEFAULT_ARGS.to_string()
    } else {
        format!("{WRY_DEFAULT_ARGS} {KEEP_AWAKE_ARGS}")
    }
}

fn plain<R: Runtime, M: Manager<R>>(manager: &M) -> bool {
    crate::dev_hooks::is_dev(manager) && std::env::var_os(PLAIN_ENV).is_some()
}

/// Applies the keep-awake settings to a window being built.
pub fn keep_awake<'a, R: Runtime, M: Manager<R>>(
    manager: &M,
    builder: WebviewWindowBuilder<'a, R, M>,
) -> WebviewWindowBuilder<'a, R, M> {
    let args = browser_args(plain(manager));
    builder
        .additional_browser_args(&args)
        .background_throttling(BackgroundThrottlingPolicy::Disabled)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_wry_defaults_and_adds_the_keep_awake_switches() {
        let args = browser_args(false);
        for part in [
            "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection",
            "--autoplay-policy=no-user-gesture-required",
            "--disable-background-timer-throttling",
            "--disable-renderer-backgrounding",
            "--disable-backgrounding-occluded-windows",
        ] {
            assert!(args.split(' ').any(|a| a == part), "missing {part}");
        }
        // One --disable-features switch only: a second one would replace the first.
        assert_eq!(args.matches("--disable-features").count(), 1);
        assert_eq!(browser_args(true), WRY_DEFAULT_ARGS);
    }

    #[test]
    fn wry_defaults_have_not_moved() {
        // If wry changes its defaults, this copy must follow (Cargo.lock pins wry).
        let lock = include_str!("../Cargo.lock").replace('\r', "");
        assert!(lock.contains("name = \"wry\"\nversion = \"0.53."), "wry is no longer 0.53: re-check WRY_DEFAULT_ARGS");
    }
}
