//! VoiceFlow: provider API keys in the OS credential store (spec §2.3).
//!
//! Windows: Credential Manager (generic credentials). macOS: the login Keychain.
//! Each key is one entry: service = the app identifier (so dev and release
//! builds keep separate keys), user = `api-key-<provider>`.
//!
//! The webview never lists or enumerates the store: it can only read, write or
//! delete the fixed provider names below.

use std::sync::LazyLock;

use keyring_core::{Entry, Error};
use tauri::AppHandle;

/// Provider names the frontend may use. Anything else is refused.
const PROVIDERS: &[&str] = &[
    "openai",
    "anthropic",
    "groq",
    "google",
    "deepgram",
    "elevenlabs",
    "mistral",
    "openrouter",
    "custom",
];

static STORE: LazyLock<Result<(), String>> = LazyLock::new(init_store);

#[allow(unreachable_code)]
fn init_store() -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        let store = windows_native_keyring_store::Store::new().map_err(|e| e.to_string())?;
        keyring_core::set_default_store(store);
        return Ok(());
    }
    #[cfg(target_os = "macos")]
    {
        let store =
            apple_native_keyring_store::keychain::Store::new().map_err(|e| e.to_string())?;
        keyring_core::set_default_store(store);
        return Ok(());
    }
    Err("This platform has no supported OS credential store".to_string())
}

fn entry(app: &AppHandle, provider: &str) -> Result<Entry, String> {
    if !PROVIDERS.contains(&provider) {
        return Err(format!("Unknown provider: {provider}"));
    }
    STORE.clone()?;
    let service = app.config().identifier.clone();
    Entry::new(&service, &format!("api-key-{provider}")).map_err(|e| e.to_string())
}

/// 0.9.19: the GitHub token for word lists in a repository. Its own entry
/// (`github-token`), outside `PROVIDERS`: the webview can store or clear it
/// through the profile commands but never read it back. Blocking: call it
/// on a worker thread.
fn github_entry(app: &AppHandle) -> Result<Entry, String> {
    STORE.clone()?;
    let service = app.config().identifier.clone();
    Entry::new(&service, "github-token").map_err(|e| e.to_string())
}

pub fn github_token(app: &AppHandle) -> Result<Option<String>, String> {
    match github_entry(app)?.get_password() {
        Ok(value) if !value.is_empty() => Ok(Some(value)),
        Ok(_) | Err(Error::NoEntry) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

/// Stores the token; an empty value deletes it.
pub fn set_github_token(app: &AppHandle, token: &str) -> Result<(), String> {
    let entry = github_entry(app)?;
    if token.is_empty() {
        return match entry.delete_credential() {
            Ok(()) | Err(Error::NoEntry) => Ok(()),
            Err(e) => Err(e.to_string()),
        };
    }
    entry.set_password(token).map_err(|e| e.to_string())
}

async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    tauri::async_runtime::spawn_blocking(f)
        .await
        .map_err(|e| e.to_string())?
}

/// Which store holds the keys, for the Settings label: "windows", "macos" or "none".
#[tauri::command]
pub fn secret_store_kind() -> &'static str {
    if STORE.is_err() {
        return "none";
    }
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "none"
    }
}

/// Reads a provider's API key. Returns `None` when no key is stored.
#[tauri::command]
pub async fn secret_get(app: AppHandle, provider: String) -> Result<Option<String>, String> {
    blocking(move || {
        let entry = entry(&app, &provider)?;
        match entry.get_password() {
            Ok(value) => Ok(Some(value)),
            Err(Error::NoEntry) => Ok(None),
            Err(e) => Err(e.to_string()),
        }
    })
    .await
}

/// Stores a provider's API key. An empty value deletes the entry.
#[tauri::command]
pub async fn secret_set(app: AppHandle, provider: String, value: String) -> Result<(), String> {
    blocking(move || {
        let entry = entry(&app, &provider)?;
        if value.is_empty() {
            return match entry.delete_credential() {
                Ok(()) | Err(Error::NoEntry) => Ok(()),
                Err(e) => Err(e.to_string()),
            };
        }
        entry.set_password(&value).map_err(|e| e.to_string())
    })
    .await
}
