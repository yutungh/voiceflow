//! VoiceFlow 0.9.22: word lists from a sync server (`sync/` in the
//! repository, "vf-sync"), the same server the Android keyboard uses.
//!
//! The server serves one profile folder over HTTPS with one bearer token per
//! device; the user and the device always come from the token. This source
//! maps the folder paths `sync::refresh_with` asks for onto its routes:
//!
//! ```text
//! open                                  GET /v1/manifest (layer hashes, edit names)
//! profiles/household/profile.json       GET /v1/layer/household
//! profiles/<user>/generated/profile.json GET /v1/layer/generated
//! profiles/<user>/profile.json          GET /v1/layer/personal
//! profiles/<user>/ops/<name>.json       GET or PUT /v1/ops/<name>.json
//! status/<user>/<device>.json           PUT /v1/status
//! ```
//!
//! The token is kept in the OS credential store (`secrets::gateway_token`)
//! and is sent to the configured server and nowhere else.

use std::collections::BTreeMap;
use std::path::Path;
use std::time::{Duration, Instant};

use serde::Deserialize;
use serde_json::Value;

use super::github::{HttpError, HttpRequest, HttpResponse, Transport};
use super::source::{Fetch, Source, Upload};
use super::sync::{read_limited, MAX_OP_BYTES};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
/// The manifest lists every edit name of the user (the server allows 40,000).
const MAX_MANIFEST_BYTES: usize = 4 * 1024 * 1024;
const MAX_ERROR_BYTES: usize = 16 * 1024;

/// A sync server's address as kept in the settings: `https://host` or
/// `https://host:port`, lower case, with no path, query, login or trailing
/// slash (the phone's rule too).
pub fn normalize_origin(input: &str) -> Result<String, String> {
    const MESSAGE: &str = "The sync server's address must be a bare https:// address, like https://sync.example.com.";
    let trimmed = input.trim().trim_end_matches('/');
    let lower = trimmed.to_ascii_lowercase();
    let Some(rest) = lower.strip_prefix("https://") else {
        return Err(MESSAGE.into());
    };
    if rest.is_empty() || rest.len() > 253 + 6 {
        return Err(MESSAGE.into());
    }
    let (host, port) = match rest.rsplit_once(':') {
        Some((host, port)) => (host, Some(port)),
        None => (rest, None),
    };
    let host_ok = !host.is_empty()
        && !host.starts_with('.')
        && !host.ends_with('.')
        && !host.contains("..")
        && host.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
    let port_ok = port.is_none_or(|p| !p.is_empty() && p.len() <= 5 && p.chars().all(|c| c.is_ascii_digit()) && p.parse::<u32>().is_ok_and(|n| (1..=65535).contains(&n)));
    if !host_ok || !port_ok {
        return Err(MESSAGE.into());
    }
    Ok(format!("https://{rest}"))
}

/// A device token as the server issues it (`vfs1.` and 43 characters).
pub fn valid_token(token: &str) -> bool {
    match token.strip_prefix("vfs1.") {
        Some(rest) => rest.len() == 43 && rest.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'),
        None => false,
    }
}

/// An edit file name the server's routes accept.
fn valid_op_name(name: &str) -> bool {
    let Some(stem) = name.strip_suffix(".json") else { return false };
    let mut chars = stem.chars();
    let first_ok = chars.next().is_some_and(|c| c.is_ascii_lowercase() || c.is_ascii_digit());
    first_ok
        && stem.len() <= 100
        && stem.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-'))
}

#[derive(Debug, Deserialize)]
struct Manifest {
    schema: String,
    version: u32,
    user: String,
    device: String,
    #[serde(default)]
    layers: BTreeMap<String, Option<LayerEntry>>,
    #[serde(default)]
    ops: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct LayerEntry {
    #[serde(default)]
    sha256: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

enum Problem {
    /// The server asked to slow down; no more requests this check.
    RateLimited,
    /// The token was refused (revoked, mistyped).
    Auth,
    Denied(String),
    NotFound,
    Conflict,
    Status(u16, String),
    Timeout,
    Network,
    TooLarge,
    /// Not sent: the time budget ran out, or requests were stopped.
    NotSent(String),
}

pub struct Options {
    /// From `normalize_origin`.
    pub origin: String,
    pub user: String,
    pub device: String,
    pub token: String,
    /// Whether this device sends its edits and status.
    pub upload: bool,
    /// No request starts after this.
    pub deadline: Instant,
}

pub struct GatewaySource {
    origin: String,
    user: String,
    device: String,
    token: String,
    upload: bool,
    deadline: Instant,
    http: Box<dyn Transport>,
    manifest: Option<Manifest>,
    stopped: Option<String>,
    issue: Option<&'static str>,
    uploads_refused: bool,
    errors: Vec<String>,
    notices: Vec<String>,
}

impl GatewaySource {
    pub fn new(options: Options, http: Box<dyn Transport>) -> Self {
        GatewaySource {
            origin: options.origin,
            user: options.user,
            device: options.device,
            token: options.token,
            upload: options.upload,
            deadline: options.deadline,
            http,
            manifest: None,
            stopped: None,
            issue: None,
            uploads_refused: false,
            errors: Vec::new(),
            notices: Vec::new(),
        }
    }

    fn remaining(&self) -> Duration {
        self.deadline.saturating_duration_since(Instant::now())
    }

    /// One request, within the budget, sorted into success (2xx) or a
    /// problem. A rate limit or a refused token stops every later request.
    fn request(&mut self, method: &'static str, route: &str, body: Option<Vec<u8>>, max: usize) -> Result<HttpResponse, Problem> {
        if let Some(why) = &self.stopped {
            return Err(Problem::NotSent(why.clone()));
        }
        let left = self.remaining();
        if left < Duration::from_secs(1) {
            return Err(Problem::NotSent("the check ran out of time".into()));
        }
        let request = HttpRequest {
            method,
            url: format!("{}{route}", self.origin),
            accept: "application/json",
            if_none_match: None,
            body,
            timeout: left.min(REQUEST_TIMEOUT),
            max_body: max,
        };
        let response = match self.http.send(&request, &self.token) {
            Ok(r) => r,
            Err(HttpError::Timeout) => return Err(Problem::Timeout),
            Err(HttpError::Network(_)) => return Err(Problem::Network),
            Err(HttpError::TooLarge) => return Err(Problem::TooLarge),
        };
        if (200..300).contains(&response.status) {
            return Ok(response);
        }
        let message = server_message(&response);
        Err(match response.status {
            401 => {
                let why = "The sync server didn't accept this computer's token (it may have been revoked). Using the last good copy.".to_string();
                self.stopped = Some(why);
                self.issue.get_or_insert("auth");
                Problem::Auth
            }
            429 => {
                let why = "The sync server asked VoiceLnq to slow down; the rest waits for the next check.".to_string();
                self.stopped = Some(why);
                self.issue.get_or_insert("rate-limited");
                Problem::RateLimited
            }
            403 => Problem::Denied(message),
            404 => Problem::NotFound,
            409 => Problem::Conflict,
            s => Problem::Status(s, message),
        })
    }

    fn describe(&self, problem: &Problem) -> String {
        match problem {
            Problem::RateLimited => "the sync server asked to wait".into(),
            Problem::Auth => "the sync server refused the token".into(),
            Problem::Denied(m) => format!("the sync server refused it ({m})"),
            Problem::NotFound => "not found on the sync server".into(),
            Problem::Conflict => "a different file already has this name on the sync server".into(),
            Problem::Status(s, m) if m.is_empty() => format!("the sync server answered {s}"),
            Problem::Status(s, m) => format!("the sync server answered {s} ({m})"),
            Problem::Timeout => "the sync server didn't answer in time".into(),
            Problem::Network => "couldn't reach the sync server".into(),
            Problem::TooLarge => "the file is over the size limit".into(),
            Problem::NotSent(why) => why.clone(),
        }
    }

    /// The layer route for a folder path of this user.
    fn layer_of(&self, rel: &str) -> Option<&'static str> {
        if rel == "profiles/household/profile.json" {
            Some("household")
        } else if rel == format!("profiles/{}/profile.json", self.user) {
            Some("personal")
        } else if rel == format!("profiles/{}/generated/profile.json", self.user) {
            Some("generated")
        } else {
            None
        }
    }

    fn ops_dir(&self) -> String {
        format!("profiles/{}/ops", self.user)
    }

    /// The edit file name for a folder path of this user's edits.
    fn op_of<'a>(&self, rel: &'a str) -> Option<&'a str> {
        let name = rel.strip_prefix(&format!("{}/", self.ops_dir()))?;
        valid_op_name(name).then_some(name)
    }

    fn refuse_uploads(&mut self, why: &str) -> Upload {
        if !self.uploads_refused {
            self.uploads_refused = true;
            self.notices.push(format!(
                "The sync server didn't take this computer's changes ({why}); they stay on this computer."
            ));
        }
        Upload::Refused(why.to_string())
    }
}

/// The server's `{"error": "..."}`, cut short. It names fields and paths,
/// never a value from the request.
fn server_message(response: &HttpResponse) -> String {
    if response.body.len() > MAX_ERROR_BYTES {
        return String::new();
    }
    serde_json::from_slice::<Value>(&response.body)
        .ok()
        .and_then(|v| v.get("error").and_then(Value::as_str).map(|s| s.chars().take(160).collect()))
        .unwrap_or_default()
}

impl Source for GatewaySource {
    fn open(&mut self) -> Result<(), String> {
        let unreachable = |what: &str| format!("Can't reach the sync server ({what}); using the last good copy.");
        let response = match self.request("GET", "/v1/manifest", None, MAX_MANIFEST_BYTES) {
            Ok(r) => r,
            Err(Problem::Auth) => {
                return Err("The sync server didn't accept this computer's token. Ask for a new one and add it in Settings > Word lists. Using the last good copy.".into());
            }
            Err(Problem::RateLimited) => {
                return Err("The sync server asked VoiceLnq to wait; it checks again later. Using the last good copy.".into());
            }
            Err(Problem::NotFound) => {
                self.issue = Some("not-found");
                return Err(unreachable("it has no word lists at this address"));
            }
            Err(problem) => {
                self.issue.get_or_insert("offline");
                let what = self.describe(&problem);
                return Err(unreachable(&what));
            }
        };
        let manifest: Manifest = match serde_json::from_slice(&response.body) {
            Ok(m) => m,
            Err(_) => {
                self.issue = Some("offline");
                return Err(unreachable("its answer wasn't a word-list listing"));
            }
        };
        if manifest.schema != "vf-sync-manifest" || manifest.version != 1 {
            self.issue = Some("offline");
            return Err(unreachable("it speaks a different version"));
        }
        // The token names the user and the device; these settings must agree,
        // or edits and status would be refused (or land under someone else).
        if manifest.user != self.user || manifest.device != self.device {
            self.issue = Some("auth");
            return Err("This computer's token belongs to another user or device than these settings name. Check Settings > Word lists. Using the last good copy.".into());
        }
        self.manifest = Some(manifest);
        Ok(())
    }

    fn issue(&self) -> Option<&'static str> {
        self.issue
    }

    fn revision(&self, rel: &str) -> Option<String> {
        // Edit files are create-only on the server (never replaced), so the
        // name is their content id: one found bad isn't downloaded again.
        if let Some(name) = self.op_of(rel) {
            return Some(format!("op:{name}"));
        }
        let layer = self.layer_of(rel)?;
        let entry = self.manifest.as_ref()?.layers.get(layer)?.as_ref()?;
        entry.sha256.as_ref().map(|sha| format!("sha256:{sha}"))
    }

    fn read(&mut self, rel: &str, max: u64) -> Fetch {
        let route = if let Some(layer) = self.layer_of(rel) {
            // The listing already says when a layer is missing or too large.
            match self.manifest.as_ref().and_then(|m| m.layers.get(layer)) {
                Some(None) => return Fetch::NotFound,
                Some(Some(entry)) if entry.error.is_some() => {
                    return Fetch::Failed(format!("the sync server says it is {}", entry.error.as_deref().unwrap_or("unreadable")));
                }
                _ => {}
            }
            format!("/v1/layer/{layer}")
        } else if let Some(name) = self.op_of(rel) {
            format!("/v1/ops/{name}")
        } else {
            return Fetch::NotFound;
        };
        let max = usize::try_from(max).unwrap_or(usize::MAX);
        match self.request("GET", &route, None, max) {
            Ok(response) => match String::from_utf8(response.body) {
                Ok(text) => Fetch::Text(text),
                Err(_) => Fetch::Failed("not UTF-8 text".into()),
            },
            Err(Problem::NotFound) => Fetch::NotFound,
            Err(problem @ (Problem::RateLimited | Problem::NotSent(_))) => Fetch::Deferred(self.describe(&problem)),
            Err(problem) => Fetch::Failed(self.describe(&problem)),
        }
    }

    fn list_json(&mut self, rel_dir: &str) -> Vec<String> {
        if rel_dir != self.ops_dir() {
            return Vec::new();
        }
        let mut names: Vec<String> = self
            .manifest
            .as_ref()
            .map(|m| m.ops.iter().filter(|n| valid_op_name(n)).cloned().collect())
            .unwrap_or_default();
        names.sort();
        names.dedup();
        names
    }

    fn uploads(&self) -> bool {
        self.upload
    }

    fn create(&mut self, rel_dir: &str, name: &str, local: &Path) -> Upload {
        if rel_dir != self.ops_dir() || !valid_op_name(name) {
            return Upload::Failed(format!("{name} is not an edit file the sync server takes"));
        }
        if self.uploads_refused {
            return Upload::Refused("uploads were refused earlier in this check".into());
        }
        let text = match read_limited(local, MAX_OP_BYTES) {
            Ok(t) => t,
            Err(e) => return Upload::Failed(e.to_string()),
        };
        match self.request("PUT", &format!("/v1/ops/{name}"), Some(text.into_bytes()), MAX_ERROR_BYTES) {
            // 201 created, 200 the same file was already there (a retry).
            Ok(_) => Upload::Done,
            Err(Problem::RateLimited | Problem::NotSent(_)) => Upload::Deferred,
            Err(Problem::Auth) => self.refuse_uploads("the token was refused"),
            Err(Problem::Denied(m)) => {
                let why = if m.is_empty() { "not allowed".to_string() } else { m };
                self.refuse_uploads(&why)
            }
            Err(Problem::Status(507, _)) => Upload::Failed("the sync server holds too many edits for this user".into()),
            Err(problem) => Upload::Failed(self.describe(&problem)),
        }
    }

    fn put_status(&mut self, _rel: &str, text: &str) -> Result<(), String> {
        match self.request("PUT", "/v1/status", Some(text.as_bytes().to_vec()), MAX_ERROR_BYTES) {
            Ok(_) => Ok(()),
            // Left for the next check on purpose.
            Err(Problem::RateLimited | Problem::NotSent(_)) => Ok(()),
            Err(problem) => Err(self.describe(&problem)),
        }
    }

    fn take_messages(&mut self) -> (Vec<String>, Vec<String>) {
        (std::mem::take(&mut self.errors), std::mem::take(&mut self.notices))
    }
}

/// The real transport: one blocking client per check, HTTPS only, no
/// redirects, and only to the configured server.
pub struct GatewayTransport {
    client: reqwest::blocking::Client,
    origin: String,
}

impl GatewayTransport {
    pub fn new(version: &str, origin: &str) -> Result<Self, String> {
        let client = reqwest::blocking::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .https_only(true)
            .user_agent(format!("VoiceFlow/{version}"))
            .connect_timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| format!("couldn't start the connection to the sync server: {}", e.without_url()))?;
        Ok(GatewayTransport { client, origin: origin.to_string() })
    }
}

impl Transport for GatewayTransport {
    fn send(&self, request: &HttpRequest, token: &str) -> Result<HttpResponse, HttpError> {
        use reqwest::header::{HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
        use std::io::Read;
        // The token goes to the configured server and nowhere else.
        if !request.url.starts_with(&format!("{}/v1/", self.origin)) {
            return Err(HttpError::Network("not the configured sync server".into()));
        }
        let method = reqwest::Method::from_bytes(request.method.as_bytes()).map_err(|_| HttpError::Network("bad method".into()))?;
        let mut auth = HeaderValue::from_str(&format!("Bearer {token}")).map_err(|_| HttpError::Network("the token has characters a header can't carry".into()))?;
        auth.set_sensitive(true);
        let mut builder = self
            .client
            .request(method, &request.url)
            .timeout(request.timeout)
            .header(AUTHORIZATION, auth)
            .header(ACCEPT, request.accept);
        if let Some(body) = &request.body {
            builder = builder.header(CONTENT_TYPE, "application/json").body(body.clone());
        }
        let response = builder.send().map_err(|e| {
            if e.is_timeout() {
                HttpError::Timeout
            } else {
                HttpError::Network(e.without_url().to_string())
            }
        })?;
        let status = response.status().as_u16();
        let headers = response
            .headers()
            .iter()
            .map(|(n, v)| (n.as_str().to_lowercase(), v.to_str().unwrap_or("").to_string()))
            .collect();
        let mut body = Vec::new();
        response
            .take(request.max_body as u64 + 1)
            .read_to_end(&mut body)
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::TimedOut {
                    HttpError::Timeout
                } else {
                    HttpError::Network(e.to_string())
                }
            })?;
        if body.len() > request.max_body {
            return Err(HttpError::TooLarge);
        }
        Ok(HttpResponse { status, headers, body })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::sync::{check, Config, Paths};
    use std::fs;
    use std::sync::{Arc, Mutex};

    // ------------------------------------------------------------ a fake server

    /// The gateway's routes, in memory, answering as `sync/src/gateway.mjs`
    /// does for one user and one device token.
    #[derive(Default)]
    struct State {
        user: String,
        device: String,
        token: String,
        layers: BTreeMap<String, String>,
        ops: BTreeMap<String, String>,
        status: Option<String>,
        /// Every request answers this status.
        force: Option<u16>,
        /// PUTs to /v1/ops answer 403.
        refuse_ops: bool,
        /// Requests left before 429 (None: no limit).
        budget: Option<usize>,
        requests: Vec<String>,
        tokens_seen: Vec<String>,
    }

    #[derive(Clone)]
    struct Fake(Arc<Mutex<State>>);

    fn sha(text: &str) -> String {
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
        for b in text.bytes() {
            hash ^= u64::from(b);
            hash = hash.wrapping_mul(0x0100_0000_01b3);
        }
        format!("{hash:016x}{:048x}", text.len())
    }

    fn reply(status: u16, body: &str) -> HttpResponse {
        HttpResponse { status, headers: Vec::new(), body: body.as_bytes().to_vec() }
    }

    impl Transport for Fake {
        fn send(&self, request: &HttpRequest, token: &str) -> Result<HttpResponse, HttpError> {
            let mut s = self.0.lock().unwrap();
            let path = request.url.strip_prefix("https://sync.example.test").expect("only the configured server").to_string();
            s.requests.push(format!("{} {path}", request.method));
            s.tokens_seen.push(token.to_string());
            if let Some(code) = s.force {
                return Ok(reply(code, r#"{"error":"forced"}"#));
            }
            if token != s.token {
                return Ok(reply(401, r#"{"error":"a valid device token is required"}"#));
            }
            if let Some(left) = s.budget.as_mut() {
                if *left == 0 {
                    return Ok(reply(429, r#"{"error":"slow down"}"#));
                }
                *left -= 1;
            }
            let body = request.body.as_ref().map(|b| String::from_utf8(b.clone()).unwrap());
            match (request.method, path.as_str()) {
                ("GET", "/v1/manifest") => {
                    let layers: serde_json::Map<String, Value> = ["household", "generated", "personal"]
                        .iter()
                        .map(|l| {
                            let v = s
                                .layers
                                .get(*l)
                                .map(|t| serde_json::json!({"sha256": sha(t), "size": t.len()}))
                                .unwrap_or(Value::Null);
                            (l.to_string(), v)
                        })
                        .collect();
                    let ops: Vec<&String> = s.ops.keys().collect();
                    let m = serde_json::json!({"schema":"vf-sync-manifest","version":1,"user":s.user,"device":s.device,"layers":layers,"ops":ops});
                    Ok(reply(200, &m.to_string()))
                }
                ("GET", p) if p.starts_with("/v1/layer/") => match s.layers.get(&p["/v1/layer/".len()..]) {
                    Some(t) => Ok(reply(200, t)),
                    None => Ok(reply(404, r#"{"error":"not found"}"#)),
                },
                ("GET", p) if p.starts_with("/v1/ops/") => match s.ops.get(&p["/v1/ops/".len()..]) {
                    Some(t) => Ok(reply(200, t)),
                    None => Ok(reply(404, r#"{"error":"not found"}"#)),
                },
                ("PUT", p) if p.starts_with("/v1/ops/") => {
                    if s.refuse_ops {
                        return Ok(reply(403, r#"{"error":"writes are off for this device"}"#));
                    }
                    let name = p["/v1/ops/".len()..].to_string();
                    let body = body.unwrap();
                    let op: Value = serde_json::from_str(&body).unwrap();
                    if !name.starts_with(&format!("{}-", s.device)) {
                        return Ok(reply(403, r#"{"error":"an edit's name must start with this device's id"}"#));
                    }
                    if op["id"].as_str() != Some(name.trim_end_matches(".json")) {
                        return Ok(reply(422, r#"{"error":"the edit's id must equal its file name"}"#));
                    }
                    if op["device"].as_str() != Some(s.device.as_str()) {
                        return Ok(reply(403, r#"{"error":"the edit's device must be this device"}"#));
                    }
                    match s.ops.get(&name) {
                        Some(existing) if *existing == body => Ok(reply(200, r#"{"ok":true,"result":"already there"}"#)),
                        Some(_) => Ok(reply(409, r#"{"error":"a different edit already has this name"}"#)),
                        None => {
                            s.ops.insert(name, body);
                            Ok(reply(201, r#"{"ok":true,"result":"created"}"#))
                        }
                    }
                }
                ("PUT", "/v1/status") => {
                    let body = body.unwrap();
                    let v: Value = serde_json::from_str(&body).unwrap();
                    if v["user"].as_str() != Some(s.user.as_str()) || v["device"].as_str() != Some(s.device.as_str()) {
                        return Ok(reply(403, r#"{"error":"status must name this device and its user"}"#));
                    }
                    s.status = Some(body);
                    Ok(reply(200, r#"{"ok":true}"#))
                }
                _ => Ok(reply(404, r#"{"error":"not found"}"#)),
            }
        }
    }

    const TOKEN: &str = "vfs1.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    const HOUSEHOLD: &str = r#"{"schema":"voiceflow-profile","schemaVersion":1,"layer":"household","user":"household",
        "terms":[{"id":"tovo","text":"Tovo","heardAs":["Toe Vo"]}]}"#;
    const PERSONAL: &str = r#"{"schema":"voiceflow-profile","schemaVersion":1,"layer":"personal","user":"sam",
        "terms":[{"id":"quorvane","text":"Quorvane","priority":80}],
        "corrections":[{"id":"core-vane","from":["Core Vane"],"to":"Quorvane"}]}"#;

    fn op(id: &str, device: &str, body: &str) -> String {
        format!(r#"{{"schema":"voiceflow-op","version":1,"id":"{id}","device":"{device}","createdAt":"2026-11-25T10:00:00Z",{body}}}"#)
    }

    struct Setup {
        _root: tempfile::TempDir,
        paths: Paths,
        fake: Fake,
        upload: bool,
        token: String,
    }

    fn setup() -> Setup {
        let root = tempfile::tempdir().unwrap();
        let config = Config {
            enabled: true,
            kind: "gateway".into(),
            gateway: "https://Sync.Example.test/".into(),
            user: "sam".into(),
            device: "mac1a2b3c4d".into(),
            ..Config::default()
        };
        assert!(config.check().is_ok());
        let paths = Paths::new(root.path(), &config);
        let state = State {
            user: "sam".into(),
            device: "mac1a2b3c4d".into(),
            token: TOKEN.into(),
            ..State::default()
        };
        let fake = Fake(Arc::new(Mutex::new(state)));
        {
            let mut s = fake.0.lock().unwrap();
            s.layers.insert("household".into(), HOUSEHOLD.into());
            s.layers.insert("personal".into(), PERSONAL.into());
        }
        Setup { _root: root, paths, fake, upload: true, token: TOKEN.into() }
    }

    impl Setup {
        fn source(&self) -> GatewaySource {
            GatewaySource::new(
                Options {
                    origin: self.paths.gateway.clone().unwrap(),
                    user: "sam".into(),
                    device: "mac1a2b3c4d".into(),
                    token: self.token.clone(),
                    upload: self.upload,
                    deadline: Instant::now() + Duration::from_secs(40),
                },
                Box::new(self.fake.clone()),
            )
        }
        fn check(&self) -> crate::profile::sync::Outcome {
            check(&self.paths, &mut self.source(), "0.9.22", "2026-11-26T10:00:00Z")
        }
        fn local_edit(&self, name: &str, text: &str) {
            let path = self.paths.local_ops().join(name);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, text).unwrap();
        }
        fn requests(&self) -> Vec<String> {
            std::mem::take(&mut self.fake.0.lock().unwrap().requests)
        }
    }

    #[test]
    fn addresses_and_tokens() {
        assert_eq!(normalize_origin("https://Sync.Example.com/").unwrap(), "https://sync.example.com");
        assert_eq!(normalize_origin(" https://sync.example.com:8443 ").unwrap(), "https://sync.example.com:8443");
        for bad in [
            "http://sync.example.com",
            "https://sync.example.com/path",
            "https://user@sync.example.com",
            "https://sync.example.com?x=1",
            "https://",
            "sync.example.com",
            "https://sync.example.com:0",
            "https://sync.example.com:99999",
            "https://sync..example.com",
        ] {
            assert!(normalize_origin(bad).is_err(), "{bad}");
        }
        assert!(valid_token(TOKEN));
        assert!(!valid_token("vfs1.short"));
        assert!(!valid_token("github_pat_0000000000000000000000000000000000000000000"));
        assert!(valid_op_name("mac1a2b3c4d-0f2e.json"));
        assert!(!valid_op_name("../x.json"));
        assert!(!valid_op_name("Upper.json"));
    }

    #[test]
    fn config_identity_and_cache_are_the_servers() {
        let s = setup();
        assert_eq!(s.paths.gateway.as_deref(), Some("https://sync.example.test"));
        let folder = Paths::new(s.paths.app_data.as_path(), &Config { enabled: true, source: "https://sync.example.test".into(), user: "sam".into(), device: "d".into(), ..Config::default() });
        assert_ne!(s.paths.scope_key(), folder.scope_key(), "a folder never shares a server's cache");
        let bad = Config { enabled: true, kind: "gateway".into(), gateway: "http://sync.example.test".into(), user: "sam".into(), device: "d".into(), ..Config::default() };
        assert!(bad.check().is_err());
    }

    #[test]
    fn reads_the_layers_and_writes_status_with_one_token() {
        let s = setup();
        let out = s.check();
        assert!(out.report.source_reachable, "{:?}", out.report.errors);
        assert!(out.report.errors.is_empty(), "{:?}", out.report.errors);
        assert_eq!(out.snapshot.terms.len(), 2);
        let state = s.fake.0.lock().unwrap();
        assert!(state.tokens_seen.iter().all(|t| t == TOKEN));
        let status: Value = serde_json::from_str(state.status.as_ref().expect("status written")).unwrap();
        assert_eq!(status["device"], "mac1a2b3c4d");
        assert_eq!(status["user"], "sam");
        // The missing generated layer is never asked for: the listing says so.
        assert!(!state.requests.iter().any(|r| r.contains("/v1/layer/generated")));
    }

    #[test]
    fn an_unchanged_layer_is_not_downloaded_again() {
        let s = setup();
        s.check();
        s.requests();
        let out = s.check();
        assert_eq!(out.snapshot.terms.len(), 2);
        let requests = s.requests();
        assert!(!requests.iter().any(|r| r.starts_with("GET /v1/layer/")), "{requests:?}");
    }

    #[test]
    fn edits_go_up_once_and_other_devices_edits_come_down() {
        let s = setup();
        s.local_edit(
            "mac1a2b3c4d-0001.json",
            &op("mac1a2b3c4d-0001", "mac1a2b3c4d", r#""type":"term","term":{"text":"Pinewood"}"#),
        );
        s.fake.0.lock().unwrap().ops.insert(
            "ph00000000-0002.json".into(),
            op("ph00000000-0002", "ph00000000", r#""type":"term","term":{"text":"Kite"}"#),
        );
        let out = s.check();
        assert!(out.report.errors.is_empty(), "{:?}", out.report.errors);
        assert_eq!(out.report.uploaded_now, 1);
        assert_eq!(out.report.pending_edits, 0);
        assert!(s.fake.0.lock().unwrap().ops.contains_key("mac1a2b3c4d-0001.json"));
        let texts: Vec<&str> = out.snapshot.terms.iter().map(|t| t.text.as_str()).collect();
        assert!(texts.contains(&"Pinewood") && texts.contains(&"Kite"), "{texts:?}");
        s.requests();
        // No second upload; this computer's own edit is read back once, as
        // from any folder, and kept.
        let again = s.check();
        assert_eq!(again.report.uploaded_now, 0);
        let requests = s.requests();
        assert!(!requests.iter().any(|r| r.starts_with("PUT /v1/ops/")), "{requests:?}");
        // From then on nothing is read again.
        s.check();
        let requests = s.requests();
        assert!(!requests.iter().any(|r| r.contains("/v1/ops/")), "{requests:?}");
    }

    #[test]
    fn refused_uploads_are_a_notice_and_the_edits_stay_here() {
        let s = setup();
        s.fake.0.lock().unwrap().refuse_ops = true;
        s.local_edit(
            "mac1a2b3c4d-0001.json",
            &op("mac1a2b3c4d-0001", "mac1a2b3c4d", r#""type":"term","term":{"text":"Pinewood"}"#),
        );
        let out = s.check();
        assert_eq!(out.report.uploaded_now, 0);
        assert_eq!(out.report.pending_edits, 1);
        assert!(out.report.notices.iter().any(|n| n.contains("stay on this computer")), "{:?}", out.report.notices);
        // Still used here.
        assert!(out.snapshot.terms.iter().any(|t| t.text == "Pinewood"));
    }

    #[test]
    fn uploads_off_sends_nothing() {
        let mut s = setup();
        s.upload = false;
        s.local_edit(
            "mac1a2b3c4d-0001.json",
            &op("mac1a2b3c4d-0001", "mac1a2b3c4d", r#""type":"term","term":{"text":"Pinewood"}"#),
        );
        let out = s.check();
        assert_eq!(out.report.uploaded_now, 0);
        let state = s.fake.0.lock().unwrap();
        assert!(state.status.is_none());
        assert!(!state.requests.iter().any(|r| r.starts_with("PUT")));
    }

    #[test]
    fn a_refused_token_reads_as_auth_and_keeps_the_last_good_copy() {
        let mut s = setup();
        assert_eq!(s.check().snapshot.terms.len(), 2);
        s.token = "vfs1.0000000000000000000000000000000000000000000".into();
        let out = s.check();
        assert!(!out.report.source_reachable);
        assert_eq!(out.report.source_issue.as_deref(), Some("auth"));
        assert_eq!(out.snapshot.terms.len(), 2, "the last good copy stays");
        // One request, then nothing more.
        assert_eq!(s.requests().iter().filter(|r| r.contains("manifest")).count(), 2);
    }

    #[test]
    fn a_token_for_another_user_is_refused_before_anything_is_read() {
        let s = setup();
        s.fake.0.lock().unwrap().user = "alex".into();
        let out = s.check();
        assert!(!out.report.source_reachable);
        assert_eq!(out.report.source_issue.as_deref(), Some("auth"));
        assert!(out.snapshot.terms.is_empty());
        let state = s.fake.0.lock().unwrap();
        assert!(state.status.is_none());
        assert_eq!(state.requests, vec!["GET /v1/manifest".to_string()]);
    }

    #[test]
    fn offline_is_offline() {
        let s = setup();
        s.fake.0.lock().unwrap().force = Some(502);
        let out = s.check();
        assert!(!out.report.source_reachable);
        assert_eq!(out.report.source_issue.as_deref(), Some("offline"));
    }

    #[test]
    fn a_rate_limit_defers_the_rest_to_the_next_check() {
        let s = setup();
        {
            let mut state = s.fake.0.lock().unwrap();
            for i in 0..5 {
                let id = format!("ph00000000-{i:04}");
                state.ops.insert(format!("{id}.json"), op(&id, "ph00000000", &format!(r#""type":"term","term":{{"text":"Word{i}"}}"#)));
            }
            // The manifest, two layers, then two edits.
            state.budget = Some(5);
        }
        let out = s.check();
        assert!(out.report.source_reachable);
        assert!(out.report.notices.iter().any(|n| n.contains("next check")), "{:?}", out.report.notices);
        s.fake.0.lock().unwrap().budget = None;
        let out = s.check();
        assert_eq!(out.snapshot.terms.len(), 7, "{:?}", out.report.errors);
    }
}
