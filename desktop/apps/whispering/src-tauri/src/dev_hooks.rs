//! VoiceFlow: test hooks for a development build only.
//!
//! A build whose identifier ends in `.dev`, `.dev2`, ... (never the shipped
//! `com.voiceflowkeyboard.desktop`) reads `VOICEFLOW_DEV_SETTINGS`, a JSON
//! object of settings, and writes it over the stored settings before the page
//! loads. It lets a test instance run beside the real one with its own keys
//! (and no sounds, bar or paste) without touching the real one's data. Any
//! other build ignores the variable.

use tauri::plugin::{Builder, TauriPlugin};
use tauri::{Manager, Runtime};

const SETTINGS_ENV: &str = "VOICEFLOW_DEV_SETTINGS";
const SETTINGS_KEY: &str = "whispering-settings";

/// True for a development identifier (last part `dev`, `dev2`, ...).
pub fn is_dev_identifier(identifier: &str) -> bool {
    identifier
        .rsplit('.')
        .next()
        .and_then(|last| last.strip_prefix("dev"))
        .is_some_and(|rest| rest.chars().all(|c| c.is_ascii_digit()))
}

pub fn is_dev<R: Runtime, M: Manager<R>>(manager: &M) -> bool {
    is_dev_identifier(&manager.config().identifier)
}

/// The page script for the overrides, or None when there are none.
pub fn settings_script(identifier: &str, overrides: Option<&str>) -> Option<String> {
    if !is_dev_identifier(identifier) {
        return None;
    }
    let value: serde_json::Value = serde_json::from_str(overrides?).ok()?;
    if !value.is_object() {
        return None;
    }
    let key = serde_json::to_string(SETTINGS_KEY).ok()?;
    Some(format!(
        "(function(){{try{{var k={key};var o={value};var c={{}};try{{c=JSON.parse(localStorage.getItem(k)||'{{}}')||{{}};}}catch(e){{}}for(var p in o){{c[p]=o[p];}}localStorage.setItem(k,JSON.stringify(c));}}catch(e){{}}}})();"
    ))
}

/// The plugin; it adds nothing unless the build is a development one and the
/// variable is set.
pub fn plugin<R: Runtime>(identifier: &str) -> TauriPlugin<R> {
    let overrides = std::env::var(SETTINGS_ENV).ok();
    let mut builder = Builder::new("vf-dev");
    if let Some(script) = settings_script(identifier, overrides.as_deref()) {
        builder = builder.js_init_script(script);
    }
    builder.build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_dev_identifier_gets_overrides() {
        let o = Some(r#"{"bar.show":false}"#);
        assert!(settings_script("com.voiceflowkeyboard.desktop", o).is_none());
        assert!(settings_script("com.voiceflowkeyboard.devtools", o).is_none());
        assert!(settings_script("com.voiceflowkeyboard.desktop.dev2", o).is_some());
        assert!(settings_script("com.voiceflowkeyboard.desktop.dev", None).is_none());
        assert!(settings_script("com.voiceflowkeyboard.desktop.dev", Some("[1]")).is_none());
        assert!(settings_script("com.voiceflowkeyboard.desktop.dev", Some("not json")).is_none());
        let s = settings_script("com.voiceflowkeyboard.desktop.dev", o).unwrap();
        assert!(s.contains("\"whispering-settings\"") && s.contains("\"bar.show\":false"));
    }
}
