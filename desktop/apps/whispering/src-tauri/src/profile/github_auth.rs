//! Connect GitHub (0.9.20): the computer signs in to the VoiceFlow GitHub App
//! with GitHub's device flow, so nobody has to make and paste a token.
//!
//! - **Device flow:** `POST github.com/login/device/code` gives a code the user
//!   types at github.com/login/device; the app polls
//!   `POST github.com/login/oauth/access_token` at GitHub's interval
//!   (`authorization_pending`, `slow_down` adds time, an expired or declined
//!   code ends it). The `device_code` stays in this process's memory; the
//!   webview sees only the code to type, the link and the state.
//! - **Tokens:** a user access token (`ghu_`, 8 hours) and a refresh token
//!   (`ghr_`, 6 months). The pair is refreshed on demand, ten minutes before
//!   the access token runs out. Refreshing **rotates** both: the new pair is
//!   stored before anything else uses it, under one lock that re-reads the
//!   store, so two callers never spend the same refresh token.
//! - **One credential:** the computer's credential store keeps one record,
//!   either a pasted token (the "Advanced" path, stored as the raw token as
//!   in 0.9.19) or the App's tokens (a small JSON record). Connecting replaces
//!   a pasted token; pasting a token replaces the connection.
//! - **No client secret** is needed for any of this, so none is in the app:
//!   GitHub lets a device-flow token be refreshed with the client ID alone.
//! - **Disconnect** forgets the tokens on this computer. Taking the App's
//!   access away on GitHub (Settings > Applications) ends it everywhere.
//!
//! Requests go to `https://github.com/login/...` (OAuth, no token sent) and
//! `https://api.github.com` (the listing of repositories), over HTTPS with
//! redirects refused. Tokens are never logged or put in a message.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::Duration;

use serde::Serialize;
use serde_json::{json, Value};

use super::github::{valid_token, HttpError, HttpRequest, HttpResponse, Transport};

/// The VoiceFlow GitHub App computers connect to (Contents: read and write).
/// Public values: a device-flow App has no secret in the app.
pub const CLIENT_ID: &str = "Iv23liVoiceFlowRWxxxx";
/// The App's name in its github.com/apps/<slug> address.
pub const APP_SLUG: &str = "voiceflow-word-lists";
/// Where the App is installed on a repository.
pub fn install_url() -> String {
    format!("https://github.com/apps/{APP_SLUG}/installations/new")
}
/// Where a user sees and revokes the Apps they authorised.
pub const AUTHORIZATIONS_URL: &str = "https://github.com/settings/apps/authorizations";
/// The only page the user is sent to for the code.
pub const DEVICE_URL: &str = "https://github.com/login/device";

pub const OAUTH_ORIGIN: &str = "https://github.com";
/// Refresh this long before the access token runs out.
pub const REFRESH_MARGIN_SECS: i64 = 10 * 60;
const MAX_OAUTH_BYTES: usize = 64 * 1024;
const OAUTH_TIMEOUT: Duration = Duration::from_secs(20);
const DEVICE_GRANT: &str = "urn:ietf:params:oauth:grant-type:device_code";

// ---------------------------------------------------------------- the credential

/// The App's tokens and when they run out (unix seconds).
#[derive(Clone, PartialEq)]
pub struct AppTokens {
    pub client_id: String,
    pub access_token: String,
    pub refresh_token: String,
    pub access_expires_at: i64,
    pub refresh_expires_at: i64,
    /// GitHub refused the refresh token: only a new Connect helps.
    pub reconnect: bool,
}

impl std::fmt::Debug for AppTokens {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AppTokens")
            .field("access_expires_at", &self.access_expires_at)
            .field("refresh_expires_at", &self.refresh_expires_at)
            .field("reconnect", &self.reconnect)
            .finish_non_exhaustive()
    }
}

/// What the credential store holds for the word lists.
#[derive(Clone, PartialEq)]
pub enum Credential {
    /// A pasted fine-grained or classic token.
    Pat(String),
    App(AppTokens),
}

impl std::fmt::Debug for Credential {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Credential::Pat(_) => f.write_str("Pat(..)"),
            Credential::App(t) => write!(f, "App({t:?})"),
        }
    }
}

fn token_chars(s: &str) -> bool {
    s.len() >= 20 && s.len() <= 255 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// A user access token from a GitHub App (`ghu_…`).
pub fn valid_access_token(token: &str) -> bool {
    token.strip_prefix("ghu_").is_some_and(token_chars)
}

/// A GitHub App refresh token (`ghr_…`).
pub fn valid_refresh_token(token: &str) -> bool {
    token.strip_prefix("ghr_").is_some_and(token_chars)
}

impl Credential {
    /// Reads what the store holds: a raw token (as 0.9.19 stored it) or the
    /// App's record. Anything else is not a credential.
    pub fn decode(raw: &str) -> Option<Credential> {
        let raw = raw.trim();
        if raw.starts_with('{') {
            let v: Value = serde_json::from_str(raw).ok()?;
            if v["v"].as_u64() != Some(1) || v["kind"].as_str() != Some("app") {
                return None;
            }
            let tokens = AppTokens {
                client_id: v["clientId"].as_str()?.to_string(),
                access_token: v["accessToken"].as_str()?.to_string(),
                refresh_token: v["refreshToken"].as_str().unwrap_or("").to_string(),
                access_expires_at: v["accessExpiresAt"].as_i64()?,
                refresh_expires_at: v["refreshExpiresAt"].as_i64()?,
                reconnect: v["reconnect"].as_bool().unwrap_or(false),
            };
            if !valid_access_token(&tokens.access_token)
                || !(tokens.refresh_token.is_empty() || valid_refresh_token(&tokens.refresh_token))
            {
                return None;
            }
            return Some(Credential::App(tokens));
        }
        valid_token(raw).then(|| Credential::Pat(raw.to_string()))
    }

    /// What goes in the store. A pasted token stays the raw token, so 0.9.19
    /// still reads it.
    pub fn encode(&self) -> String {
        match self {
            Credential::Pat(token) => token.clone(),
            Credential::App(t) => json!({
                "v": 1,
                "kind": "app",
                "clientId": t.client_id,
                "accessToken": t.access_token,
                "refreshToken": t.refresh_token,
                "accessExpiresAt": t.access_expires_at,
                "refreshExpiresAt": t.refresh_expires_at,
                "reconnect": t.reconnect,
            })
            .to_string(),
        }
    }

    /// For Settings: `pat`, `app` or `reconnect`.
    pub fn kind(&self, now: i64) -> &'static str {
        match self {
            Credential::Pat(_) => "pat",
            Credential::App(t) if t.reconnect || t.refresh_expires_at <= now && t.access_expires_at <= now => "reconnect",
            Credential::App(_) => "app",
        }
    }
}

// ---------------------------------------------------------------- the store

/// The computer's credential store, one entry. An empty value deletes it.
pub trait Store: Send + Sync {
    fn load(&self) -> Result<Option<String>, String>;
    fn save(&self, value: &str) -> Result<(), String>;
}

// ---------------------------------------------------------------- OAuth requests

/// Sends one form POST to `https://github.com<path>` with no token.
pub trait OAuth: Send + Sync {
    fn post(&self, path: &str, form: &[(&str, &str)]) -> Result<HttpResponse, HttpError>;
}

fn form_encode(form: &[(&str, &str)]) -> String {
    let enc = |s: &str| {
        let mut out = String::new();
        for b in s.bytes() {
            if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
                out.push(b as char);
            } else {
                out.push_str(&format!("%{b:02X}"));
            }
        }
        out
    };
    form.iter().map(|(k, v)| format!("{}={}", enc(k), enc(v))).collect::<Vec<_>>().join("&")
}

/// The real OAuth client: github.com only, HTTPS, no redirects, small answers.
pub struct ReqwestOAuth {
    client: reqwest::blocking::Client,
}

impl ReqwestOAuth {
    pub fn new(version: &str) -> Result<Self, String> {
        let client = reqwest::blocking::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .https_only(true)
            .user_agent(format!("VoiceFlow/{version}"))
            .connect_timeout(Duration::from_secs(10))
            .timeout(OAUTH_TIMEOUT)
            .build()
            .map_err(|e| format!("couldn't start the connection to GitHub: {}", e.without_url()))?;
        Ok(ReqwestOAuth { client })
    }
}

impl OAuth for ReqwestOAuth {
    fn post(&self, path: &str, form: &[(&str, &str)]) -> Result<HttpResponse, HttpError> {
        use std::io::Read;
        if !matches!(path, "/login/device/code" | "/login/oauth/access_token") {
            return Err(HttpError::Network("not a GitHub sign-in address".into()));
        }
        let response = self
            .client
            .post(format!("{OAUTH_ORIGIN}{path}"))
            .header(reqwest::header::ACCEPT, "application/json")
            .header(reqwest::header::CONTENT_TYPE, "application/x-www-form-urlencoded")
            .body(form_encode(form))
            .send()
            .map_err(|e| if e.is_timeout() { HttpError::Timeout } else { HttpError::Network(e.without_url().to_string()) })?;
        let status = response.status().as_u16();
        let headers = response
            .headers()
            .iter()
            .map(|(n, v)| (n.as_str().to_lowercase(), v.to_str().unwrap_or("").to_string()))
            .collect();
        let mut body = Vec::new();
        response
            .take(MAX_OAUTH_BYTES as u64 + 1)
            .read_to_end(&mut body)
            .map_err(|e| HttpError::Network(e.to_string()))?;
        if body.len() > MAX_OAUTH_BYTES {
            return Err(HttpError::TooLarge);
        }
        Ok(HttpResponse { status, headers, body })
    }
}

/// Why signing in or refreshing didn't work, worded for the user.
#[derive(Clone, Debug, PartialEq)]
pub enum AuthError {
    /// No answer, or GitHub is down: try again.
    Network(String),
    /// The App's setup is wrong (device flow off, a wrong client ID). Signing
    /// in again won't help.
    Config(String),
}

impl AuthError {
    pub fn message(&self) -> String {
        match self {
            AuthError::Network(m) | AuthError::Config(m) => m.clone(),
        }
    }
}

fn network_error(e: HttpError) -> AuthError {
    AuthError::Network(match e {
        HttpError::Timeout => "GitHub didn't answer in time. Try again.".into(),
        _ => "Can't reach GitHub. Check the internet connection and try again.".into(),
    })
}

/// GitHub's answer as JSON. Its errors come back as HTTP 200 with `error`.
fn oauth_json(response: &HttpResponse) -> Result<Value, AuthError> {
    let value: Value = serde_json::from_slice(&response.body).map_err(|_| {
        if response.status >= 500 {
            AuthError::Network("GitHub is having trouble right now. Try again in a few minutes.".into())
        } else {
            AuthError::Network(format!("GitHub's answer couldn't be read (HTTP {}).", response.status))
        }
    })?;
    if response.status >= 500 {
        return Err(AuthError::Network("GitHub is having trouble right now. Try again in a few minutes.".into()));
    }
    Ok(value)
}

fn config_error(code: &str) -> AuthError {
    AuthError::Config(match code {
        "device_flow_disabled" => "VoiceFlow's GitHub App doesn't allow this way of signing in. Use an access token (Advanced) for now.".into(),
        "incorrect_client_credentials" | "unsupported_grant_type" | "incorrect_device_code" => {
            "GitHub refused VoiceFlow's sign-in request. Use an access token (Advanced) for now.".into()
        }
        _ => "GitHub refused the sign-in. Use an access token (Advanced) for now.".into(),
    })
}

/// The code the user types on GitHub.
#[derive(Clone, PartialEq)]
pub struct DeviceCode {
    /// Kept in this process only; never sent to the webview or logged.
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

impl std::fmt::Debug for DeviceCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceCode").field("user_code", &self.user_code).finish_non_exhaustive()
    }
}

/// Asks GitHub for a code to show.
pub fn request_code(http: &dyn OAuth, client_id: &str) -> Result<DeviceCode, AuthError> {
    let response = http.post("/login/device/code", &[("client_id", client_id)]).map_err(network_error)?;
    let v = oauth_json(&response)?;
    if let Some(code) = v["error"].as_str() {
        return Err(config_error(code));
    }
    let user_code = v["user_code"].as_str().unwrap_or("");
    let device_code = v["device_code"].as_str().unwrap_or("");
    let ok_code = |s: &str, max: usize| !s.is_empty() && s.len() <= max && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-');
    if !ok_code(user_code, 16) || !ok_code(device_code, 100) {
        return Err(AuthError::Network("GitHub's answer couldn't be read.".into()));
    }
    // The user is only ever sent to GitHub's own page.
    let verification_uri = match v["verification_uri"].as_str() {
        Some(uri) if uri == DEVICE_URL => uri.to_string(),
        _ => DEVICE_URL.to_string(),
    };
    Ok(DeviceCode {
        device_code: device_code.to_string(),
        user_code: user_code.to_string(),
        verification_uri,
        expires_in: v["expires_in"].as_u64().unwrap_or(900).clamp(60, 1800),
        interval: v["interval"].as_u64().unwrap_or(5).clamp(1, 60),
    })
}

/// One answer while waiting for the user.
#[derive(Debug, PartialEq)]
pub enum Poll {
    Pending,
    /// Asked too often: wait this many seconds between polls from now on.
    SlowDown(u64),
    Done(AppTokens),
    Expired,
    Denied,
    Failed(AuthError),
}

/// The tokens in a successful answer, with their expiry times.
fn tokens_from(v: &Value, client_id: &str, now: i64) -> Option<AppTokens> {
    let access = v["access_token"].as_str()?;
    if !valid_access_token(access) {
        return None;
    }
    let refresh = v["refresh_token"].as_str().unwrap_or("");
    if !refresh.is_empty() && !valid_refresh_token(refresh) {
        return None;
    }
    // No expiry given: tokens that don't expire (the App's setting is off).
    let access_expires_at = v["expires_in"].as_i64().map(|s| now.saturating_add(s)).unwrap_or(i64::MAX);
    let refresh_expires_at = v["refresh_token_expires_in"].as_i64().map(|s| now.saturating_add(s)).unwrap_or(i64::MAX);
    Some(AppTokens {
        client_id: client_id.to_string(),
        access_token: access.to_string(),
        refresh_token: refresh.to_string(),
        access_expires_at,
        refresh_expires_at,
        reconnect: false,
    })
}

/// Asks once whether the user has entered the code.
pub fn poll(http: &dyn OAuth, client_id: &str, device_code: &str, interval: u64, now: i64) -> Poll {
    let response = match http.post(
        "/login/oauth/access_token",
        &[("client_id", client_id), ("device_code", device_code), ("grant_type", DEVICE_GRANT)],
    ) {
        Ok(r) => r,
        Err(e) => return Poll::Failed(network_error(e)),
    };
    let v = match oauth_json(&response) {
        Ok(v) => v,
        Err(e) => return Poll::Failed(e),
    };
    match v["error"].as_str() {
        Some("authorization_pending") => Poll::Pending,
        Some("slow_down") => Poll::SlowDown(v["interval"].as_u64().filter(|&i| i > interval).unwrap_or(interval + 5).min(120)),
        Some("expired_token") | Some("token_expired") => Poll::Expired,
        Some("access_denied") => Poll::Denied,
        Some(code) => Poll::Failed(config_error(code)),
        None => match tokens_from(&v, client_id, now) {
            Some(tokens) => Poll::Done(tokens),
            None => Poll::Failed(AuthError::Network("GitHub's answer couldn't be read.".into())),
        },
    }
}

/// What a refresh came to.
#[derive(Debug, PartialEq)]
pub enum Refreshed {
    Ok(AppTokens),
    /// The refresh token is no good any more (expired, revoked, used): only a
    /// new Connect helps.
    Rejected,
    /// Try again later (no network, GitHub down).
    Retry(AuthError),
}

pub fn refresh(http: &dyn OAuth, client_id: &str, refresh_token: &str, now: i64) -> Refreshed {
    let response = match http.post(
        "/login/oauth/access_token",
        &[("client_id", client_id), ("grant_type", "refresh_token"), ("refresh_token", refresh_token)],
    ) {
        Ok(r) => r,
        Err(e) => return Refreshed::Retry(network_error(e)),
    };
    let v = match oauth_json(&response) {
        Ok(v) => v,
        Err(e) => return Refreshed::Retry(e),
    };
    match v["error"].as_str() {
        Some("bad_refresh_token") | Some("unauthorized") | Some("incorrect_client_credentials") => Refreshed::Rejected,
        Some(code) => Refreshed::Retry(config_error(code)),
        None => match tokens_from(&v, client_id, now) {
            Some(tokens) => Refreshed::Ok(tokens),
            None => Refreshed::Retry(AuthError::Network("GitHub's answer couldn't be read.".into())),
        },
    }
}

// ---------------------------------------------------------------- the vault

/// A token to use for one operation.
#[derive(Clone)]
pub struct Access {
    pub token: String,
    /// The App's token (it can be refreshed); false for a pasted token.
    pub app: bool,
    /// Which credential this came from: an explicit change since then means
    /// the operation's results must not touch the new credential.
    pub generation: u64,
    /// The new tokens couldn't be saved: they work until VoiceFlow quits.
    pub warning: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum AccessError {
    /// Nothing stored: connect or paste a token.
    Missing,
    /// The connection ended (refused or expired refresh token).
    Reconnect,
    /// The credential was replaced or removed while the operation ran.
    Replaced,
    /// The credential store couldn't be read or written.
    Store(String),
    /// GitHub couldn't be asked for new tokens now.
    Retry(AuthError),
}

pub const RECONNECT_MESSAGE: &str =
    "VoiceFlow's connection to GitHub has ended. Press Connect GitHub in Settings > Word lists; using the last good copy.";

impl AccessError {
    pub fn message(&self) -> String {
        match self {
            AccessError::Missing => "Connect GitHub (or add an access token) in Settings > Word lists; using the last good copy.".into(),
            AccessError::Reconnect => RECONNECT_MESSAGE.into(),
            AccessError::Replaced => "The GitHub connection changed while the word lists were read; they are read again now.".into(),
            AccessError::Store(e) => format!("Couldn't read the GitHub sign-in from this computer's credential store ({e}); using the last good copy."),
            AccessError::Retry(e) => format!("{} Using the last good copy.", e.message().trim_end_matches(" Try again.")),
        }
    }

    /// For Settings (Test connection, the repository list).
    pub fn short(&self) -> String {
        match self {
            AccessError::Missing => "Press Connect GitHub (or add an access token) first.".into(),
            AccessError::Reconnect => "VoiceFlow's connection to GitHub has ended. Press Connect GitHub to connect again.".into(),
            AccessError::Replaced => "The GitHub connection changed meanwhile. Try again.".into(),
            AccessError::Store(e) => format!("Couldn't read the GitHub sign-in from this computer's credential store ({e})."),
            AccessError::Retry(e) => e.message(),
        }
    }

    /// The short code the sync report and Settings use.
    pub fn issue(&self) -> &'static str {
        match self {
            AccessError::Retry(AuthError::Network(_)) => "offline",
            _ => "auth",
        }
    }
}

#[derive(Default)]
struct VaultState {
    loaded: bool,
    current: Option<Credential>,
    /// The current credential is newer than what the store holds.
    unsaved: bool,
    generation: u64,
}

/// The one credential, cached in memory, behind one lock. Every load,
/// refresh and save happens under it; an operation then uses its token
/// without holding the lock.
#[derive(Default)]
pub struct Vault {
    state: Mutex<VaultState>,
}

pub static VAULT: LazyLock<Vault> = LazyLock::new(Vault::default);

const UNSAVED_WARNING: &str =
    "The new GitHub sign-in couldn't be saved in this computer's credential store; if VoiceFlow restarts, press Connect GitHub again.";

impl Vault {
    fn load(state: &mut VaultState, store: &dyn Store) -> Result<(), AccessError> {
        if state.loaded {
            return Ok(());
        }
        let raw = store.load().map_err(AccessError::Store)?;
        state.current = raw.as_deref().and_then(Credential::decode);
        state.loaded = true;
        Ok(())
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, VaultState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Tries again to save tokens that couldn't be saved before.
    fn retry_save(state: &mut VaultState, store: &dyn Store) -> Option<String> {
        if !state.unsaved {
            return None;
        }
        let encoded = state.current.as_ref().map(Credential::encode).unwrap_or_default();
        match store.save(&encoded) {
            Ok(()) => {
                state.unsaved = false;
                None
            }
            Err(_) => Some(UNSAVED_WARNING.to_string()),
        }
    }

    /// Stores rotated tokens before anyone uses them. When the store fails,
    /// the new pair is kept here (the old one no longer works).
    fn adopt(state: &mut VaultState, store: &dyn Store, tokens: AppTokens) -> Option<String> {
        let credential = Credential::App(tokens);
        let saved = store.save(&credential.encode());
        state.current = Some(credential);
        state.unsaved = saved.is_err();
        saved.err().map(|_| UNSAVED_WARNING.to_string())
    }

    fn mark_reconnect(state: &mut VaultState, store: &dyn Store) {
        if let Some(Credential::App(t)) = &mut state.current {
            t.reconnect = true;
            let encoded = state.current.as_ref().map(Credential::encode).unwrap_or_default();
            if store.save(&encoded).is_err() {
                state.unsaved = true;
            }
        }
    }

    fn refresh_locked(state: &mut VaultState, store: &dyn Store, http: &dyn OAuth, tokens: &AppTokens, now: i64) -> Result<Access, AccessError> {
        if tokens.refresh_token.is_empty() || tokens.refresh_expires_at <= now {
            Self::mark_reconnect(state, store);
            return Err(AccessError::Reconnect);
        }
        match refresh(http, &tokens.client_id, &tokens.refresh_token, now) {
            Refreshed::Ok(new) => {
                let token = new.access_token.clone();
                let warning = Self::adopt(state, store, new);
                Ok(Access { token, app: true, generation: state.generation, warning })
            }
            Refreshed::Rejected => {
                Self::mark_reconnect(state, store);
                Err(AccessError::Reconnect)
            }
            Refreshed::Retry(e) => Err(AccessError::Retry(e)),
        }
    }

    /// A token for one operation, refreshed first when it runs out within
    /// ten minutes.
    pub fn access(&self, store: &dyn Store, http: &dyn OAuth, now: i64) -> Result<Access, AccessError> {
        let mut state = self.lock();
        Self::load(&mut state, store)?;
        let warning = Self::retry_save(&mut state, store);
        match state.current.clone() {
            None => Err(AccessError::Missing),
            Some(Credential::Pat(token)) => Ok(Access { token, app: false, generation: state.generation, warning }),
            Some(Credential::App(t)) if t.reconnect => Err(AccessError::Reconnect),
            Some(Credential::App(t)) if t.access_expires_at.saturating_sub(REFRESH_MARGIN_SECS) > now => {
                Ok(Access { token: t.access_token, app: true, generation: state.generation, warning })
            }
            Some(Credential::App(t)) => Self::refresh_locked(&mut state, store, http, &t, now),
        }
    }

    /// GitHub refused `failed` (401). If someone already replaced or rotated
    /// it, the current token is returned without asking GitHub; otherwise
    /// the pair is refreshed once. A refused refresh means reconnecting.
    pub fn after_unauthorized(&self, store: &dyn Store, http: &dyn OAuth, failed: &Access, now: i64) -> Result<Access, AccessError> {
        let mut state = self.lock();
        Self::load(&mut state, store)?;
        if state.generation != failed.generation {
            return Err(AccessError::Replaced);
        }
        match state.current.clone() {
            None => Err(AccessError::Missing),
            Some(Credential::Pat(_)) => Err(AccessError::Replaced),
            Some(Credential::App(t)) if t.reconnect => Err(AccessError::Reconnect),
            Some(Credential::App(t)) if t.access_token != failed.token => {
                Ok(Access { token: t.access_token, app: true, generation: state.generation, warning: None })
            }
            Some(Credential::App(t)) => Self::refresh_locked(&mut state, store, http, &t, now),
        }
    }

    /// The second refusal of the same, still current token: reconnect.
    pub fn refused_again(&self, store: &dyn Store, failed: &Access) {
        let mut state = self.lock();
        if state.generation != failed.generation {
            return;
        }
        if matches!(&state.current, Some(Credential::App(t)) if t.access_token == failed.token) {
            Self::mark_reconnect(&mut state, store);
        }
    }

    /// An explicit change: a pasted token, a new connection, or `None` to
    /// forget. Operations that started before it leave the new one alone.
    pub fn replace(&self, store: &dyn Store, credential: Option<Credential>) -> Result<(), String> {
        let mut state = self.lock();
        let encoded = credential.as_ref().map(Credential::encode).unwrap_or_default();
        store.save(&encoded)?;
        state.current = credential;
        state.loaded = true;
        state.unsaved = false;
        state.generation += 1;
        Ok(())
    }

    /// For Settings: `""` (nothing), `pat`, `app` or `reconnect`.
    pub fn kind(&self, store: &dyn Store, now: i64) -> Result<&'static str, String> {
        let mut state = self.lock();
        Self::load(&mut state, store).map_err(|e| match e {
            AccessError::Store(m) => m,
            other => other.message(),
        })?;
        Ok(state.current.as_ref().map(|c| c.kind(now)).unwrap_or(""))
    }

    #[cfg(test)]
    fn forget_cache(&self) {
        *self.lock() = VaultState::default();
    }
}

// ---------------------------------------------------------------- one Connect attempt

/// Time, for the wait between polls (a fake one in tests).
pub trait Clock {
    /// Seconds since some fixed moment (monotonic).
    fn elapsed(&self) -> u64;
    fn unix(&self) -> i64;
    /// Sleeps, or less when `cancelled` is set.
    fn sleep(&self, secs: u64, cancelled: &AtomicBool);
}

pub struct RealClock {
    start: std::time::Instant,
}

impl RealClock {
    pub fn new() -> Self {
        RealClock { start: std::time::Instant::now() }
    }
}

impl Default for RealClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for RealClock {
    fn elapsed(&self) -> u64 {
        self.start.elapsed().as_secs()
    }
    fn unix(&self) -> i64 {
        super::sync::now_unix()
    }
    fn sleep(&self, secs: u64, cancelled: &AtomicBool) {
        let end = std::time::Instant::now() + Duration::from_secs(secs);
        while !cancelled.load(Ordering::SeqCst) && std::time::Instant::now() < end {
            std::thread::sleep(Duration::from_millis(200));
        }
    }
}

/// How a Connect attempt ended.
#[derive(Debug, PartialEq)]
pub enum Outcome {
    Connected(AppTokens),
    Expired,
    Denied,
    Cancelled,
    Failed(AuthError),
}

/// Waits for the user to enter the code: polls at GitHub's interval, slows
/// down when asked, stops at the code's expiry or when cancelled. A network
/// hiccup is tried again until the code expires.
pub fn wait_for_user(http: &dyn OAuth, client_id: &str, code: &DeviceCode, cancelled: &AtomicBool, clock: &dyn Clock) -> Outcome {
    let started = clock.elapsed();
    let mut interval = code.interval.max(1);
    let mut failures = 0;
    loop {
        clock.sleep(interval, cancelled);
        if cancelled.load(Ordering::SeqCst) {
            return Outcome::Cancelled;
        }
        if clock.elapsed().saturating_sub(started) >= code.expires_in {
            return Outcome::Expired;
        }
        match poll(http, client_id, &code.device_code, interval, clock.unix()) {
            Poll::Pending => failures = 0,
            Poll::SlowDown(next) => interval = next,
            Poll::Done(tokens) => return Outcome::Connected(tokens),
            Poll::Expired => return Outcome::Expired,
            Poll::Denied => return Outcome::Denied,
            Poll::Failed(AuthError::Network(m)) => {
                failures += 1;
                if failures >= 5 {
                    return Outcome::Failed(AuthError::Network(m));
                }
            }
            Poll::Failed(e) => return Outcome::Failed(e),
        }
    }
}

/// What the webview sees of an attempt (no device code, no token).
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectState {
    pub id: u64,
    /// `waiting`, `connected`, `expired`, `denied`, `cancelled` or `failed`.
    pub state: String,
    pub user_code: String,
    pub verification_uri: String,
    /// Unix seconds.
    pub expires_at: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

struct Attempt {
    view: ConnectState,
    cancelled: std::sync::Arc<AtomicBool>,
}

/// The one Connect attempt this process runs at a time.
#[derive(Default)]
pub struct Attempts {
    current: Mutex<Option<Attempt>>,
    next_id: Mutex<u64>,
}

pub static ATTEMPTS: LazyLock<Attempts> = LazyLock::new(Attempts::default);

impl Attempts {
    fn lock(&self) -> std::sync::MutexGuard<'_, Option<Attempt>> {
        self.current.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A new attempt (a running one is cancelled).
    pub fn begin(&self, code: &DeviceCode, now: i64) -> (ConnectState, std::sync::Arc<AtomicBool>) {
        let id = {
            let mut next = self.next_id.lock().unwrap_or_else(|e| e.into_inner());
            *next += 1;
            *next
        };
        let cancelled = std::sync::Arc::new(AtomicBool::new(false));
        let view = ConnectState {
            id,
            state: "waiting".into(),
            user_code: code.user_code.clone(),
            verification_uri: code.verification_uri.clone(),
            expires_at: now.saturating_add(code.expires_in as i64),
            message: None,
        };
        let mut current = self.lock();
        if let Some(old) = current.take() {
            old.cancelled.store(true, Ordering::SeqCst);
        }
        *current = Some(Attempt { view: view.clone(), cancelled: cancelled.clone() });
        (view, cancelled)
    }

    pub fn status(&self, id: u64) -> Option<ConnectState> {
        self.lock().as_ref().filter(|a| a.view.id == id).map(|a| a.view.clone())
    }

    /// Disconnect: whatever attempt is waiting stops and saves nothing.
    pub fn cancel_all(&self) {
        let id = self.lock().as_ref().map(|a| a.view.id);
        if let Some(id) = id {
            self.cancel(id);
        }
    }

    pub fn cancel(&self, id: u64) {
        let mut current = self.lock();
        if let Some(a) = current.as_mut().filter(|a| a.view.id == id) {
            a.cancelled.store(true, Ordering::SeqCst);
            if a.view.state == "waiting" {
                a.view.state = "cancelled".into();
            }
        }
    }

    /// Ends attempt `id`. A connection is saved only while the attempt is
    /// still the current one and not cancelled, checked under its lock, so a
    /// cancelled or replaced attempt never overwrites the credential.
    pub fn finish(&self, id: u64, outcome: Outcome, save: impl FnOnce(AppTokens) -> Result<(), String>) {
        let mut current = self.lock();
        let Some(attempt) = current.as_mut().filter(|a| a.view.id == id) else {
            return;
        };
        if attempt.cancelled.load(Ordering::SeqCst) {
            attempt.view.state = "cancelled".into();
            return;
        }
        let (state, message) = match outcome {
            Outcome::Connected(tokens) => match save(tokens) {
                Ok(()) => ("connected", None),
                Err(e) => ("failed", Some(format!("Connected, but this computer's credential store couldn't keep the sign-in ({e})."))),
            },
            Outcome::Expired => ("expired", Some("The code ran out before it was entered. Press Connect GitHub to get a new one.".to_string())),
            Outcome::Denied => ("denied", Some("GitHub says the request was cancelled there. Press Connect GitHub to try again.".to_string())),
            Outcome::Cancelled => ("cancelled", None),
            Outcome::Failed(e) => ("failed", Some(e.message())),
        };
        attempt.view.state = state.into();
        attempt.view.message = message;
    }
}

// ---------------------------------------------------------------- choosing a repository

/// One repository the App may use, for the picker.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RepoChoice {
    pub full_name: String,
    pub private: bool,
    pub default_branch: String,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ListError {
    /// The token was refused (401).
    Unauthorized,
    Other(String),
}

const MAX_PAGES: u32 = 10;

fn get_json(http: &dyn Transport, url: String, token: &str) -> Result<Value, ListError> {
    let request = HttpRequest {
        method: "GET",
        url,
        accept: "application/vnd.github+json",
        if_none_match: None,
        body: None,
        timeout: Duration::from_secs(20),
        max_body: 2 * 1024 * 1024,
    };
    let response = http.send(&request, token).map_err(|e| {
        ListError::Other(match e {
            HttpError::Timeout => "GitHub didn't answer in time.".into(),
            HttpError::Network(_) => "Can't reach GitHub. Check the internet connection.".into(),
            HttpError::TooLarge => "GitHub's answer was larger than expected.".into(),
        })
    })?;
    match response.status {
        200..=299 => serde_json::from_slice(&response.body).map_err(|_| ListError::Other("GitHub's answer couldn't be read.".into())),
        401 => Err(ListError::Unauthorized),
        403 | 429 => Err(ListError::Other("GitHub asked VoiceFlow to slow down; try again in a few minutes.".into())),
        s => Err(ListError::Other(format!("GitHub answered with an error (HTTP {s})."))),
    }
}

/// The repositories this sign-in reaches through the App: its installations,
/// then each installation's repositories (pages of 100, built from the fixed
/// API address, never from a link GitHub sends).
pub fn list_repos(http: &dyn Transport, api: &str, token: &str) -> Result<Vec<RepoChoice>, ListError> {
    let api = api.trim_end_matches('/');
    let mut installations: Vec<u64> = Vec::new();
    for page in 1..=MAX_PAGES {
        let v = get_json(http, format!("{api}/user/installations?per_page=100&page={page}"), token)?;
        let items = v["installations"].as_array().cloned().unwrap_or_default();
        installations.extend(items.iter().filter_map(|i| i["id"].as_u64()));
        let total = v["total_count"].as_u64().unwrap_or(0);
        if items.is_empty() || installations.len() as u64 >= total {
            break;
        }
    }
    let mut seen = std::collections::BTreeSet::new();
    let mut repos = Vec::new();
    for id in installations {
        let mut count = 0u64;
        for page in 1..=MAX_PAGES {
            let v = get_json(http, format!("{api}/user/installations/{id}/repositories?per_page=100&page={page}"), token)?;
            let items = v["repositories"].as_array().cloned().unwrap_or_default();
            count += items.len() as u64;
            for r in &items {
                let (Some(rid), Some(name)) = (r["id"].as_u64(), r["full_name"].as_str()) else { continue };
                if seen.insert(rid) {
                    repos.push(RepoChoice {
                        full_name: name.to_string(),
                        private: r["private"].as_bool().unwrap_or(false),
                        default_branch: r["default_branch"].as_str().unwrap_or("main").to_string(),
                    });
                }
            }
            if items.is_empty() || count >= v["total_count"].as_u64().unwrap_or(0) {
                break;
            }
        }
    }
    repos.sort_by(|a, b| a.full_name.to_lowercase().cmp(&b.full_name.to_lowercase()));
    Ok(repos)
}

// ---------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::collections::VecDeque;
    use std::sync::Arc;

    const ACCESS: &str = "ghu_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    const REFRESH: &str = "ghr_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR";
    const PAT: &str = "github_pat_11AAAAAAA0000000000000_abcdefghijklmnopqrstuvwxyz";

    fn reply(status: u16, body: Value) -> HttpResponse {
        HttpResponse { status, headers: vec![], body: body.to_string().into_bytes() }
    }

    /// GitHub's OAuth endpoints, scripted: each POST takes the next answer.
    #[derive(Default)]
    struct FakeOAuth {
        answers: Mutex<VecDeque<Result<HttpResponse, HttpError>>>,
        requests: Mutex<Vec<(String, String)>>,
    }

    impl FakeOAuth {
        fn with(answers: Vec<Result<HttpResponse, HttpError>>) -> Self {
            FakeOAuth { answers: Mutex::new(answers.into()), requests: Mutex::default() }
        }
        fn count(&self) -> usize {
            self.requests.lock().unwrap().len()
        }
    }

    impl OAuth for FakeOAuth {
        fn post(&self, path: &str, form: &[(&str, &str)]) -> Result<HttpResponse, HttpError> {
            self.requests.lock().unwrap().push((path.to_string(), form_encode(form)));
            self.answers.lock().unwrap().pop_front().unwrap_or_else(|| Ok(reply(200, json!({"error": "authorization_pending"}))))
        }
    }

    fn tokens_json(n: u32) -> Value {
        json!({
            "access_token": format!("ghu_{n:0>36}"),
            "expires_in": 28800,
            "refresh_token": format!("ghr_{n:0>76}"),
            "refresh_token_expires_in": 15897600,
            "scope": "",
            "token_type": "bearer"
        })
    }

    #[derive(Default)]
    struct MemStore {
        value: Mutex<Option<String>>,
        fail_saves: AtomicBool,
        saves: Mutex<u32>,
    }

    impl Store for MemStore {
        fn load(&self) -> Result<Option<String>, String> {
            Ok(self.value.lock().unwrap().clone())
        }
        fn save(&self, value: &str) -> Result<(), String> {
            *self.saves.lock().unwrap() += 1;
            if self.fail_saves.load(Ordering::SeqCst) {
                return Err("store unavailable".into());
            }
            *self.value.lock().unwrap() = (!value.is_empty()).then(|| value.to_string());
            Ok(())
        }
    }

    fn app(access: &str, refresh: &str, expires: i64) -> Credential {
        Credential::App(AppTokens {
            client_id: CLIENT_ID.into(),
            access_token: access.into(),
            refresh_token: refresh.into(),
            access_expires_at: expires,
            refresh_expires_at: expires + 15_000_000,
            reconnect: false,
        })
    }

    struct FakeClock {
        now: Cell<u64>,
        sleeps: Mutex<Vec<u64>>,
    }

    impl FakeClock {
        fn new() -> Self {
            FakeClock { now: Cell::new(0), sleeps: Mutex::default() }
        }
    }

    impl Clock for FakeClock {
        fn elapsed(&self) -> u64 {
            self.now.get()
        }
        fn unix(&self) -> i64 {
            1_800_000_000 + self.now.get() as i64
        }
        fn sleep(&self, secs: u64, _cancelled: &AtomicBool) {
            self.sleeps.lock().unwrap().push(secs);
            self.now.set(self.now.get() + secs);
        }
    }

    fn code(expires_in: u64) -> DeviceCode {
        DeviceCode {
            device_code: "3584d83530557fdd1f46af8289938c8ef79f9dc5".into(),
            user_code: "WDJB-MJHT".into(),
            verification_uri: DEVICE_URL.into(),
            expires_in,
            interval: 5,
        }
    }

    #[test]
    fn credentials_round_trip_and_a_0_9_19_token_still_reads() {
        assert_eq!(Credential::decode(PAT), Some(Credential::Pat(PAT.into())));
        assert_eq!(Credential::Pat(PAT.into()).encode(), PAT, "a pasted token is stored as it was in 0.9.19");
        let c = app(ACCESS, REFRESH, 1_800_000_000);
        assert_eq!(Credential::decode(&c.encode()), Some(c.clone()));
        assert_eq!(Credential::decode("not a token"), None);
        assert_eq!(Credential::decode(r#"{"v":2,"kind":"app"}"#), None);
        // Debug output never shows a token.
        let shown = format!("{c:?} {:?}", Credential::Pat(PAT.into()));
        assert!(!shown.contains("ghu_") && !shown.contains("ghr_") && !shown.contains("github_pat_"), "{shown}");
        assert!(!format!("{:?}", code(900)).contains("3584d835"));
    }

    #[test]
    fn the_code_request_sends_only_the_client_id_and_keeps_users_on_githubs_page() {
        let http = FakeOAuth::with(vec![Ok(reply(200, json!({
            "device_code": "3584d83530557fdd1f46af8289938c8ef79f9dc5",
            "user_code": "WDJB-MJHT",
            "verification_uri": "https://evil.example/login/device",
            "expires_in": 900,
            "interval": 5
        })))]);
        let c = request_code(&http, CLIENT_ID).unwrap();
        assert_eq!(c.user_code, "WDJB-MJHT");
        assert_eq!(c.verification_uri, DEVICE_URL);
        let requests = http.requests.lock().unwrap();
        assert_eq!(requests[0], ("/login/device/code".to_string(), format!("client_id={CLIENT_ID}")));
    }

    #[test]
    fn a_disabled_device_flow_is_a_setup_problem_not_a_retry() {
        let http = FakeOAuth::with(vec![Ok(reply(200, json!({"error": "device_flow_disabled"})))]);
        assert!(matches!(request_code(&http, CLIENT_ID), Err(AuthError::Config(_))));
    }

    #[test]
    fn waiting_polls_at_the_interval_slows_down_and_connects() {
        let http = FakeOAuth::with(vec![
            Ok(reply(200, json!({"error": "authorization_pending"}))),
            Ok(reply(200, json!({"error": "slow_down", "interval": 10}))),
            Ok(reply(200, json!({"error": "slow_down"}))),
            Ok(reply(200, json!({"error": "authorization_pending"}))),
            Ok(reply(200, tokens_json(1))),
        ]);
        let clock = FakeClock::new();
        let outcome = wait_for_user(&http, CLIENT_ID, &code(900), &AtomicBool::new(false), &clock);
        let Outcome::Connected(tokens) = outcome else { panic!("{outcome:?}") };
        assert!(tokens.access_token.starts_with("ghu_"));
        assert_eq!(tokens.access_expires_at, clock.unix() + 28800);
        // 5, 5, then 10 as asked, then 10 + 5 when no interval is given.
        assert_eq!(*clock.sleeps.lock().unwrap(), vec![5, 5, 10, 15, 15]);
        let body = http.requests.lock().unwrap()[0].1.clone();
        assert!(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
        assert!(!body.contains("secret"));
    }

    #[test]
    fn waiting_ends_at_expiry_denial_or_cancel() {
        let clock = FakeClock::new();
        let http = FakeOAuth::default();
        assert_eq!(wait_for_user(&http, CLIENT_ID, &code(60), &AtomicBool::new(false), &clock), Outcome::Expired);
        assert!(http.count() <= 12);

        let http = FakeOAuth::with(vec![Ok(reply(200, json!({"error": "token_expired"})))]);
        assert_eq!(wait_for_user(&http, CLIENT_ID, &code(900), &AtomicBool::new(false), &FakeClock::new()), Outcome::Expired);
        let http = FakeOAuth::with(vec![Ok(reply(200, json!({"error": "expired_token"})))]);
        assert_eq!(wait_for_user(&http, CLIENT_ID, &code(900), &AtomicBool::new(false), &FakeClock::new()), Outcome::Expired);
        let http = FakeOAuth::with(vec![Ok(reply(200, json!({"error": "access_denied"})))]);
        assert_eq!(wait_for_user(&http, CLIENT_ID, &code(900), &AtomicBool::new(false), &FakeClock::new()), Outcome::Denied);

        let http = FakeOAuth::default();
        assert_eq!(wait_for_user(&http, CLIENT_ID, &code(900), &AtomicBool::new(true), &FakeClock::new()), Outcome::Cancelled);
        assert_eq!(http.count(), 0, "a cancelled attempt asks nothing");
    }

    #[test]
    fn a_short_network_hiccup_keeps_waiting() {
        let http = FakeOAuth::with(vec![
            Err(HttpError::Timeout),
            Err(HttpError::Network("x".into())),
            Ok(reply(502, json!({}))),
            Ok(reply(200, tokens_json(2))),
        ]);
        assert!(matches!(wait_for_user(&http, CLIENT_ID, &code(900), &AtomicBool::new(false), &FakeClock::new()), Outcome::Connected(_)));
    }

    #[test]
    fn a_cancelled_or_replaced_attempt_never_saves() {
        let attempts = Attempts::default();
        let (first, _) = attempts.begin(&code(900), 0);
        let (second, _) = attempts.begin(&code(900), 0);
        let saved = Cell::new(0);
        attempts.finish(first.id, Outcome::Connected(match app(ACCESS, REFRESH, 1) { Credential::App(t) => t, _ => unreachable!() }), |_| {
            saved.set(saved.get() + 1);
            Ok(())
        });
        assert_eq!(saved.get(), 0, "the first attempt was replaced");
        attempts.cancel(second.id);
        attempts.finish(second.id, Outcome::Connected(match app(ACCESS, REFRESH, 1) { Credential::App(t) => t, _ => unreachable!() }), |_| {
            saved.set(saved.get() + 1);
            Ok(())
        });
        assert_eq!(saved.get(), 0, "a cancelled attempt that completes late saves nothing");
        assert_eq!(attempts.status(second.id).unwrap().state, "cancelled");

        let (third, _) = attempts.begin(&code(900), 0);
        attempts.finish(third.id, Outcome::Connected(match app(ACCESS, REFRESH, 1) { Credential::App(t) => t, _ => unreachable!() }), |_| Ok(()));
        let view = attempts.status(third.id).unwrap();
        assert_eq!(view.state, "connected");
        let json = serde_json::to_string(&view).unwrap();
        assert!(!json.contains("ghu_") && !json.contains("3584d835"), "the page sees no token or device code: {json}");
    }

    #[test]
    fn a_fresh_token_is_used_without_asking_github() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = FakeOAuth::default();
        let access = vault.access(&store, &http, 10_000 - REFRESH_MARGIN_SECS - 1).unwrap();
        assert_eq!(access.token, ACCESS);
        assert_eq!(http.count(), 0);
    }

    #[test]
    fn refreshing_stores_the_rotated_pair_before_it_is_used() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = FakeOAuth::with(vec![Ok(reply(200, tokens_json(7)))]);
        let access = vault.access(&store, &http, 9_900).unwrap();
        assert_eq!(access.token, format!("ghu_{:0>36}", 7));
        let stored = Credential::decode(store.value.lock().unwrap().as_deref().unwrap()).unwrap();
        let Credential::App(t) = stored else { panic!() };
        assert_eq!(t.access_token, access.token);
        assert_eq!(t.refresh_token, format!("ghr_{:0>76}", 7));
        let body = http.requests.lock().unwrap()[0].1.clone();
        assert!(body.contains("grant_type=refresh_token") && body.contains(REFRESH) && !body.contains("secret"));
        // A second caller reads the rotated pair: nothing is spent twice.
        let again = vault.access(&store, &http, 9_901).unwrap();
        assert_eq!(again.token, access.token);
        assert_eq!(http.count(), 1);
    }

    #[test]
    fn concurrent_callers_refresh_once() {
        let store = Arc::new(MemStore::default());
        let vault = Arc::new(Vault::default());
        vault.replace(&*store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = Arc::new(FakeOAuth::with(vec![Ok(reply(200, tokens_json(3)))]));
        let handles: Vec<_> = (0..6)
            .map(|_| {
                let (store, vault, http) = (store.clone(), vault.clone(), http.clone());
                std::thread::spawn(move || vault.access(&*store, &*http, 9_990).unwrap().token)
            })
            .collect();
        let tokens: Vec<String> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        assert!(tokens.iter().all(|t| *t == format!("ghu_{:0>36}", 3)));
        assert_eq!(http.count(), 1, "one refresh token, spent once");
    }

    #[test]
    fn a_refused_refresh_means_reconnect_and_keeps_the_record() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = FakeOAuth::with(vec![Ok(reply(200, json!({"error": "bad_refresh_token"})))]);
        assert_eq!(vault.access(&store, &http, 9_999).err(), Some(AccessError::Reconnect));
        assert_eq!(vault.kind(&store, 9_999).unwrap(), "reconnect");
        assert!(store.value.lock().unwrap().is_some(), "the record stays so Settings can say Reconnect");
        assert_eq!(vault.access(&store, &http, 9_999).err(), Some(AccessError::Reconnect));
        assert_eq!(http.count(), 1, "a refused refresh token is not tried again");
        // After a restart too.
        vault.forget_cache();
        assert_eq!(vault.kind(&store, 9_999).unwrap(), "reconnect");
    }

    #[test]
    fn an_offline_refresh_is_retried_later_not_a_disconnect() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = FakeOAuth::with(vec![Err(HttpError::Timeout), Ok(reply(200, tokens_json(4)))]);
        assert!(matches!(vault.access(&store, &http, 9_999), Err(AccessError::Retry(AuthError::Network(_)))));
        assert_eq!(vault.kind(&store, 9_999).unwrap(), "app");
        assert!(vault.access(&store, &http, 9_999).is_ok());
    }

    #[test]
    fn rotated_tokens_that_cant_be_saved_are_kept_in_memory_and_saved_later() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        store.fail_saves.store(true, Ordering::SeqCst);
        let http = FakeOAuth::with(vec![Ok(reply(200, tokens_json(5)))]);
        let access = vault.access(&store, &http, 9_999).unwrap();
        assert!(access.warning.is_some());
        // The old pair no longer works on GitHub: never refreshed again from the store.
        let again = vault.access(&store, &http, 10_000).unwrap();
        assert_eq!(again.token, access.token);
        assert_eq!(http.count(), 1);
        store.fail_saves.store(false, Ordering::SeqCst);
        let later = vault.access(&store, &http, 10_001).unwrap();
        assert!(later.warning.is_none());
        assert!(store.value.lock().unwrap().as_deref().unwrap().contains(&access.token));
    }

    #[test]
    fn a_401_after_someone_else_rotated_reuses_their_token() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = FakeOAuth::with(vec![Ok(reply(200, tokens_json(8))), Ok(reply(200, tokens_json(9)))]);
        let old = vault.access(&store, &http, 0).unwrap();
        // Another caller rotates (near expiry).
        let newer = vault.access(&store, &http, 9_999).unwrap();
        let retried = vault.after_unauthorized(&store, &http, &old, 10_000).unwrap();
        assert_eq!(retried.token, newer.token);
        assert_eq!(http.count(), 1, "no second refresh");
        // The still-current token refused: one refresh.
        let fresh = vault.after_unauthorized(&store, &http, &retried, 10_000).unwrap();
        assert_eq!(fresh.token, format!("ghu_{:0>36}", 9));
        assert_eq!(http.count(), 2);
        // A stale operation's second refusal doesn't poison the newer token.
        vault.refused_again(&store, &retried);
        assert_eq!(vault.kind(&store, 10_000).unwrap(), "app");
        // The current one refused again: reconnect.
        vault.refused_again(&store, &fresh);
        assert_eq!(vault.kind(&store, 10_000).unwrap(), "reconnect");
    }

    #[test]
    fn an_explicit_change_wins_over_operations_already_running() {
        let store = MemStore::default();
        let vault = Vault::default();
        vault.replace(&store, Some(app(ACCESS, REFRESH, 10_000))).unwrap();
        let http = FakeOAuth::default();
        let running = vault.access(&store, &http, 0).unwrap();
        vault.replace(&store, Some(Credential::Pat(PAT.into()))).unwrap();
        assert_eq!(vault.after_unauthorized(&store, &http, &running, 1).err(), Some(AccessError::Replaced));
        vault.refused_again(&store, &running);
        assert_eq!(vault.kind(&store, 1).unwrap(), "pat");
        let pat = vault.access(&store, &http, 1).unwrap();
        assert!(!pat.app && pat.token == PAT);
        vault.replace(&store, None).unwrap();
        assert_eq!(vault.access(&store, &http, 1).err(), Some(AccessError::Missing));
        assert!(store.value.lock().unwrap().is_none(), "disconnect forgets the tokens here");
        assert_eq!(http.count(), 0);
    }

    // ------------------------------------------------------------ the picker

    struct FakeApi {
        pages: std::collections::BTreeMap<String, (u16, Value)>,
        seen: Mutex<Vec<String>>,
    }

    impl Transport for FakeApi {
        fn send(&self, request: &HttpRequest, token: &str) -> Result<HttpResponse, HttpError> {
            assert_eq!(token, ACCESS);
            self.seen.lock().unwrap().push(request.url.clone());
            let (status, body) = self.pages.get(&request.url).cloned().unwrap_or((404, json!({})));
            Ok(reply(status, body))
        }
    }

    #[test]
    fn the_picker_lists_every_installed_repository_once() {
        let api = "https://api.test";
        let repo = |id: u64, name: &str| json!({"id": id, "full_name": name, "private": true, "default_branch": "trunk"});
        let mut pages = std::collections::BTreeMap::new();
        pages.insert(format!("{api}/user/installations?per_page=100&page=1"), (200, json!({"total_count": 2, "installations": [{"id": 11}, {"id": 12}]})));
        let first: Vec<Value> = (0..100).map(|i| repo(1000 + i, &format!("sam/r{i:03}"))).collect();
        pages.insert(format!("{api}/user/installations/11/repositories?per_page=100&page=1"), (200, json!({"total_count": 101, "repositories": first})));
        pages.insert(format!("{api}/user/installations/11/repositories?per_page=100&page=2"), (200, json!({"total_count": 101, "repositories": [repo(5, "sam/Words")]})));
        pages.insert(format!("{api}/user/installations/12/repositories?per_page=100&page=1"), (200, json!({"total_count": 2, "repositories": [repo(5, "sam/Words"), repo(6, "family/lists")]})));
        let http = FakeApi { pages, seen: Mutex::default() };
        let repos = list_repos(&http, api, ACCESS).unwrap();
        assert_eq!(repos.len(), 102);
        assert_eq!(repos[0].full_name, "family/lists");
        assert_eq!(repos[0].default_branch, "trunk");
        assert_eq!(repos.iter().filter(|r| r.full_name == "sam/Words").count(), 1);
        assert!(http.seen.lock().unwrap().iter().all(|u| u.starts_with(api)));
    }

    #[test]
    fn the_picker_says_when_nothing_is_installed_and_when_the_token_is_refused() {
        let api = "https://api.test";
        let mut pages = std::collections::BTreeMap::new();
        pages.insert(format!("{api}/user/installations?per_page=100&page=1"), (200, json!({"total_count": 0, "installations": []})));
        let http = FakeApi { pages, seen: Mutex::default() };
        assert_eq!(list_repos(&http, api, ACCESS).unwrap(), vec![]);
        let mut pages = std::collections::BTreeMap::new();
        pages.insert(format!("{api}/user/installations?per_page=100&page=1"), (401, json!({"message": "Bad credentials"})));
        let http = FakeApi { pages, seen: Mutex::default() };
        assert_eq!(list_repos(&http, api, ACCESS), Err(ListError::Unauthorized));
    }

    #[test]
    fn the_install_link_names_the_app() {
        assert_eq!(install_url(), format!("https://github.com/apps/{APP_SLUG}/installations/new"));
        assert!(!CLIENT_ID.is_empty());
    }
}
