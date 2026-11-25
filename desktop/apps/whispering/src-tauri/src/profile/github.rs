//! The word lists in a private GitHub repository (docs/GITHUB-SYNC.md).
//!
//! The repository has the same layout as a word-list folder. One check:
//!
//! - **Listing:** `GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1`
//!   with the last ETag. An unchanged branch answers 304, which GitHub does
//!   not count against the rate limit. A truncated listing is refused (the
//!   last good copy stays), never read in part.
//! - **Files:** `GET /repos/{owner}/{repo}/git/blobs/{sha}` (raw), only for
//!   files whose blob differs from the one already accepted; sizes are checked
//!   from the listing before anything is downloaded.
//! - **Edits:** `PUT /repos/{owner}/{repo}/contents/{path}` without a `sha`,
//!   so GitHub refuses to replace a file that is already there (422). Any
//!   unclear answer is settled by reading the file back and comparing.
//! - **Status:** the same PUT with the file's `sha`; on a conflict (409/422)
//!   the current `sha` is read once and the write tried again. It is written
//!   when something other than its times changed, when the copy on GitHub
//!   isn't the one this device wrote, or once a day (a heartbeat), so the
//!   repository's history stays readable.
//!
//! The token travels only in the `Authorization` header (marked sensitive),
//! to `api.github.com` only, with redirects refused. It is never written to
//! a file, a message or a log. When GitHub asks to wait (`Retry-After`, a
//! used-up rate limit), no request is made until then, across checks.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::model::{parse_profile, valid_name, Layer};
use super::source::{Fetch, Source, Upload};
use super::sync::{iso_from_unix, read_limited, write_atomic, MAX_OP_BYTES, MAX_PROFILE_BYTES};

pub const API: &str = "https://api.github.com";
/// The listing of one branch is at most this large (GitHub stops at 7 MB).
const MAX_TREE_BYTES: usize = 8 * 1024 * 1024;
const MAX_JSON_BYTES: usize = 256 * 1024;
/// No request is given longer than this, whatever the budget left.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
/// Uploads (edits and status) start only with at least this much time left.
const WRITE_MARGIN: Duration = Duration::from_secs(8);
/// Edit uploads tried per check at most (attempts, retries included).
pub const MAX_PUTS_PER_CHECK: usize = 20;
/// GitHub asks for a pause between writes in a burst.
const WRITE_PAUSE: Duration = Duration::from_secs(1);
/// An unchanged status is written again after this long (a daily heartbeat).
const STATUS_HEARTBEAT_SECS: i64 = 23 * 3600;
/// A rate limit without a time to wait: try again after this long.
const RATE_LIMIT_FALLBACK_SECS: i64 = 60;

// ---------------------------------------------------------------- the repo

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Repo {
    pub owner: String,
    pub name: String,
    pub branch: String,
}

impl Repo {
    /// `owner/name` and a branch, checked.
    pub fn parse(repo: &str, branch: &str) -> Result<Repo, String> {
        let repo = repo.trim().trim_end_matches('/');
        let repo = repo
            .strip_prefix("https://github.com/")
            .or_else(|| repo.strip_prefix("github.com/"))
            .unwrap_or(repo);
        let repo = repo.strip_suffix(".git").unwrap_or(repo);
        let Some((owner, name)) = repo.split_once('/') else {
            return Err("Enter the repository as owner/name (for example sam-lee/voiceflow-words).".into());
        };
        let owner_ok = !owner.is_empty()
            && owner.len() <= 39
            && owner.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
            && !owner.starts_with('-');
        let name_ok = !name.is_empty()
            && name.len() <= 100
            && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
            && name != "."
            && name != "..";
        if !owner_ok || !name_ok {
            return Err("Enter the repository as owner/name (for example sam-lee/voiceflow-words).".into());
        }
        let branch = branch.trim();
        if !valid_branch(branch) {
            return Err("Enter the branch name (usually main).".into());
        }
        Ok(Repo { owner: owner.to_string(), name: name.to_string(), branch: branch.to_string() })
    }

    /// Which word lists these are, for caches and edit owners: owner and
    /// name ignore case on GitHub, a branch name doesn't.
    pub fn identity(&self) -> String {
        format!("github:{}/{}@{}", self.owner.to_lowercase(), self.name.to_lowercase(), self.branch)
    }

    pub fn label(&self) -> String {
        format!("{}/{}", self.owner, self.name)
    }

    fn base(&self, api: &str) -> String {
        format!("{api}/repos/{}/{}", self.owner, self.name)
    }
}

/// A branch name VoiceFlow accepts: letters, digits, `.`, `_`, `-` and `/`
/// between parts. (Git allows more; these are the names that travel safely
/// in a URL without surprises.)
pub fn valid_branch(branch: &str) -> bool {
    !branch.is_empty()
        && branch.len() <= 200
        && branch.split('/').all(|part| {
            !part.is_empty()
                && !part.starts_with('.')
                && !part.ends_with(".lock")
                && part.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
        })
        && !branch.contains("..")
}

/// A GitHub token's shape: a fine-grained token (`github_pat_…`) or a
/// classic one (`ghp_…`). Nothing else is stored.
pub fn valid_token(token: &str) -> bool {
    let ok_chars = |s: &str| s.len() >= 20 && s.len() <= 255 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
    token
        .strip_prefix("github_pat_")
        .map(ok_chars)
        .or_else(|| token.strip_prefix("ghp_").map(ok_chars))
        .unwrap_or(false)
}

/// A path segment, percent-encoded (`/` too).
fn encode(segment: &str) -> String {
    let mut out = String::new();
    for b in segment.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// A repository path, each segment encoded.
fn encode_path(rel: &str) -> String {
    rel.split('/').map(encode).collect::<Vec<_>>().join("/")
}

// ---------------------------------------------------------------- HTTP

pub struct HttpRequest {
    pub method: &'static str,
    pub url: String,
    pub accept: &'static str,
    pub if_none_match: Option<String>,
    pub body: Option<Vec<u8>>,
    pub timeout: Duration,
    pub max_body: usize,
}

#[derive(Clone, Debug)]
pub struct HttpResponse {
    pub status: u16,
    /// Names in lower case.
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }
    fn json(&self) -> Option<Value> {
        serde_json::from_slice(&self.body).ok()
    }
}

#[derive(Debug, PartialEq)]
pub enum HttpError {
    Timeout,
    Network(String),
    TooLarge,
}

/// Sends one request with the token in the `Authorization` header.
pub trait Transport: Send {
    fn send(&self, request: &HttpRequest, token: &str) -> Result<HttpResponse, HttpError>;
}

/// The real transport: one blocking client per check, HTTPS only, no
/// redirects. Used on the profile worker thread, never in async code.
pub struct ReqwestTransport {
    client: reqwest::blocking::Client,
}

impl ReqwestTransport {
    pub fn new(version: &str) -> Result<Self, String> {
        let client = reqwest::blocking::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .https_only(true)
            .user_agent(format!("VoiceFlow/{version}"))
            .connect_timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| format!("couldn't start the connection to GitHub: {}", e.without_url()))?;
        Ok(ReqwestTransport { client })
    }
}

impl Transport for ReqwestTransport {
    fn send(&self, request: &HttpRequest, token: &str) -> Result<HttpResponse, HttpError> {
        use reqwest::header::{HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE, IF_NONE_MATCH};
        use std::io::Read;
        let method = reqwest::Method::from_bytes(request.method.as_bytes()).map_err(|_| HttpError::Network("bad method".into()))?;
        let mut auth = HeaderValue::from_str(&format!("Bearer {token}")).map_err(|_| HttpError::Network("the token has characters a header can't carry".into()))?;
        auth.set_sensitive(true);
        let mut builder = self
            .client
            .request(method, &request.url)
            .timeout(request.timeout)
            .header(AUTHORIZATION, auth)
            .header(ACCEPT, request.accept)
            .header("X-GitHub-Api-Version", "2022-11-28");
        if let Some(tag) = &request.if_none_match {
            if let Ok(value) = HeaderValue::from_str(tag) {
                builder = builder.header(IF_NONE_MATCH, value);
            }
        }
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

// ---------------------------------------------------------------- what is kept

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Entry {
    pub sha: String,
    #[serde(default)]
    pub size: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusRecord {
    /// The blob this device wrote last.
    pub sha: String,
    /// That status without its times.
    pub fingerprint: Value,
    pub written_at: i64,
}

/// Kept per repository, branch and user (`profile/cache/<scope>/github.json`).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Meta {
    /// The listing's ETag, for a 304 next time.
    pub etag: String,
    /// The files this device reads or writes, by path: the household file,
    /// this user's layers and edits, and this device's status.
    pub entries: BTreeMap<String, Entry>,
    /// No request before this time (unix seconds): GitHub asked to wait.
    pub not_before: i64,
    pub status: Option<StatusRecord>,
}

pub fn load_meta(path: &Path) -> Meta {
    read_limited(path, MAX_TREE_BYTES as u64)
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

/// The paths a device of `user` (`device`) uses.
fn wanted(path: &str, user: &str, device: &str) -> bool {
    let ops = format!("profiles/{user}/ops/");
    path == "profiles/household/profile.json"
        || path == format!("profiles/{user}/profile.json")
        || path == format!("profiles/{user}/generated/profile.json")
        || path == format!("status/{user}/{device}.json")
        || path.strip_prefix(&ops).is_some_and(|rest| !rest.is_empty() && !rest.contains('/'))
}

#[derive(Deserialize)]
struct Tree {
    #[serde(default)]
    tree: Vec<TreeEntry>,
    #[serde(default)]
    truncated: bool,
}

#[derive(Deserialize)]
struct TreeEntry {
    path: String,
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    sha: String,
    #[serde(default)]
    size: Option<u64>,
}

/// The users a listing has (`profiles/<user>/`, not `household`).
fn users_in(tree: &Tree) -> Vec<String> {
    let mut users: Vec<String> = tree
        .tree
        .iter()
        .filter(|e| e.kind == "tree")
        .filter_map(|e| e.path.strip_prefix("profiles/"))
        .filter(|rest| !rest.contains('/'))
        .filter(|name| *name != "household" && valid_name(name))
        .map(str::to_string)
        .collect();
    users.sort();
    users.dedup();
    users
}

// ---------------------------------------------------------------- answers

enum Problem {
    /// GitHub asked to wait; no more requests this check.
    RateLimited,
    /// The token was refused (expired, revoked, mistyped).
    Auth,
    /// 403 without a rate limit: the token may not do this.
    Denied,
    NotFound,
    Status(u16),
    Timeout,
    Network,
    TooLarge,
    /// Not sent: the time budget ran out, or requests were stopped.
    NotSent(String),
}

/// What a rate-limited answer asks for: the time to wait until, or `None`
/// when it isn't a rate limit. Every valid time GitHub gives is honoured.
pub fn rate_limit_until(response: &HttpResponse, now: i64) -> Option<i64> {
    let status = response.status;
    let remaining_zero = response.header("x-ratelimit-remaining") == Some("0");
    let retry_after = response.header("retry-after");
    let says_rate = String::from_utf8_lossy(&response.body).to_lowercase().contains("rate limit");
    let limited = status == 429 || (status == 403 && (remaining_zero || retry_after.is_some() || says_rate));
    if !limited {
        return None;
    }
    let mut until: Option<i64> = None;
    if let Some(secs) = retry_after.and_then(|v| v.trim().parse::<i64>().ok()).filter(|s| *s >= 0) {
        until = Some(now.saturating_add(secs));
    }
    if remaining_zero {
        if let Some(reset) = response.header("x-ratelimit-reset").and_then(|v| v.trim().parse::<i64>().ok()) {
            if reset > now {
                until = Some(until.map_or(reset, |u| u.max(reset)));
            }
        }
    }
    Some(until.unwrap_or(now + RATE_LIMIT_FALLBACK_SECS))
}

// ---------------------------------------------------------------- the source

pub struct GitHubSource {
    repo: Repo,
    user: String,
    device: String,
    token: String,
    http: Box<dyn Transport>,
    api: String,
    meta_path: PathBuf,
    meta: Meta,
    upload: bool,
    deadline: Instant,
    now: i64,
    /// When this check started (`now` is the same moment in unix seconds).
    started: Instant,
    /// Requests stop for the rest of this check (rate limit, refused token).
    stopped: Option<String>,
    uploads_refused: bool,
    issue: Option<&'static str>,
    puts: usize,
    last_write: Option<Instant>,
    errors: Vec<String>,
    notices: Vec<String>,
}

pub struct Options {
    pub repo: Repo,
    pub user: String,
    pub device: String,
    pub token: String,
    pub upload: bool,
    /// The cache file for this repository, branch and user.
    pub meta_path: PathBuf,
    /// No request starts after this.
    pub deadline: Instant,
    /// Unix seconds.
    pub now: i64,
}

impl GitHubSource {
    pub fn new(options: Options, http: Box<dyn Transport>) -> Self {
        Self::with_api(options, http, API)
    }

    pub fn with_api(options: Options, http: Box<dyn Transport>, api: &str) -> Self {
        let meta = load_meta(&options.meta_path);
        GitHubSource {
            repo: options.repo,
            user: options.user,
            device: options.device,
            token: options.token,
            http,
            api: api.trim_end_matches('/').to_string(),
            meta_path: options.meta_path,
            meta,
            upload: options.upload,
            deadline: options.deadline,
            now: options.now,
            started: Instant::now(),
            stopped: None,
            uploads_refused: false,
            issue: None,
            puts: 0,
            last_write: None,
            errors: Vec::new(),
            notices: Vec::new(),
        }
    }

    pub fn meta(&self) -> &Meta {
        &self.meta
    }

    fn remaining(&self) -> Duration {
        self.deadline.saturating_duration_since(Instant::now())
    }

    fn wait_message(&self, until: i64) -> String {
        format!(
            "GitHub asked VoiceFlow to wait; it checks again after {} (UTC). Using the last good copy.",
            iso_from_unix(until).replace('T', " ").trim_end_matches('Z')
        )
    }

    /// One request, within the budget, its answer sorted into success
    /// (2xx and 304) or a problem. A rate limit stops every later request.
    fn request(&mut self, method: &'static str, url: String, accept: &'static str, etag: Option<String>, body: Option<Vec<u8>>, max: usize) -> Result<HttpResponse, Problem> {
        if let Some(why) = &self.stopped {
            return Err(Problem::NotSent(why.clone()));
        }
        let left = self.remaining();
        if left < Duration::from_secs(1) {
            return Err(Problem::NotSent("the check ran out of time".into()));
        }
        let request = HttpRequest {
            method,
            url,
            accept,
            if_none_match: etag,
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
        if (200..300).contains(&response.status) || response.status == 304 {
            return Ok(response);
        }
        // A wait GitHub gives runs from when its answer came, not from the start of the check.
        let received = self.now.saturating_add(self.started.elapsed().as_secs() as i64);
        if let Some(until) = rate_limit_until(&response, received) {
            self.meta.not_before = self.meta.not_before.max(until);
            let message = self.wait_message(self.meta.not_before);
            self.stopped = Some(message.clone());
            self.issue.get_or_insert("rate-limited");
            self.save_meta();
            return Err(Problem::RateLimited);
        }
        Err(match response.status {
            401 => {
                let message = "GitHub didn't accept the token (it may have expired or been revoked). Add a new one in Settings > Word lists.".to_string();
                self.stopped = Some(message);
                Problem::Auth
            }
            403 => Problem::Denied,
            404 => Problem::NotFound,
            s => Problem::Status(s),
        })
    }

    fn save_meta(&self) {
        if let Ok(text) = serde_json::to_string(&self.meta) {
            let _ = write_atomic(&self.meta_path, text.as_bytes());
        }
    }

    fn not_found_message(&self) -> String {
        format!(
            "GitHub can't find {} (branch {}) with this token. Check the name and branch, and that the token was made for this repository.",
            self.repo.label(),
            self.repo.branch
        )
    }

    fn describe(&self, problem: &Problem) -> String {
        match problem {
            Problem::NotSent(why) => why.clone(),
            Problem::RateLimited | Problem::Auth => self.stopped.clone().unwrap_or_else(|| "GitHub refused the request.".into()),
            Problem::Denied => format!("GitHub refused this token access to {}.", self.repo.label()),
            Problem::NotFound => self.not_found_message(),
            Problem::Status(s) => format!("GitHub answered with an error (HTTP {s})."),
            Problem::Timeout => "GitHub didn't answer in time.".into(),
            Problem::Network => "Can't reach GitHub.".into(),
            Problem::TooLarge => "GitHub's answer was larger than expected.".into(),
        }
    }

    fn blob(&mut self, sha: &str, max: u64) -> Result<String, Problem> {
        let url = format!("{}/git/blobs/{}", self.repo.base(&self.api), encode(sha));
        let response = self.request("GET", url, "application/vnd.github.raw+json", None, None, max as usize)?;
        String::from_utf8(response.body).map_err(|_| Problem::Status(415))
    }

    /// A file by path, as it is on the branch now (not the listing): used to
    /// settle an upload whose answer was unclear. `None`: not there.
    fn read_back(&mut self, rel: &str) -> Result<Option<String>, Problem> {
        let url = format!("{}/contents/{}?ref={}", self.repo.base(&self.api), encode_path(rel), encode(&self.repo.branch));
        match self.request("GET", url, "application/vnd.github.raw+json", None, None, MAX_OP_BYTES as usize) {
            Ok(r) => Ok(Some(String::from_utf8_lossy(&r.body).to_string())),
            Err(Problem::NotFound) => Ok(None),
            Err(p) => Err(p),
        }
    }

    /// The current blob sha of a path (`None`: not there).
    fn current_sha(&mut self, rel: &str) -> Result<Option<String>, Problem> {
        let url = format!("{}/contents/{}?ref={}", self.repo.base(&self.api), encode_path(rel), encode(&self.repo.branch));
        match self.request("GET", url, "application/vnd.github+json", None, None, MAX_JSON_BYTES) {
            Ok(r) => Ok(r.json().and_then(|v| v["sha"].as_str().map(str::to_string))),
            Err(Problem::NotFound) => Ok(None),
            Err(p) => Err(p),
        }
    }

    fn pause_between_writes(&mut self) {
        if let Some(last) = self.last_write {
            let since = last.elapsed();
            if since < WRITE_PAUSE {
                std::thread::sleep(WRITE_PAUSE - since);
            }
        }
        self.last_write = Some(Instant::now());
    }

    fn put(&mut self, rel: &str, text: &str, sha: Option<&str>, message: String) -> Result<HttpResponse, Problem> {
        self.pause_between_writes();
        let mut body = serde_json::json!({
            "message": message,
            "content": base64::engine::general_purpose::STANDARD.encode(text.as_bytes()),
            "branch": self.repo.branch,
        });
        if let Some(sha) = sha {
            body["sha"] = Value::from(sha);
        }
        let url = format!("{}/contents/{}", self.repo.base(&self.api), encode_path(rel));
        let bytes = serde_json::to_vec(&body).map_err(|_| Problem::Status(0))?;
        self.request("PUT", url, "application/vnd.github+json", None, Some(bytes), MAX_JSON_BYTES)
    }

    fn note_written(&mut self, rel: &str, response: &HttpResponse) -> Option<String> {
        let sha = response.json().and_then(|v| v["content"]["sha"].as_str().map(str::to_string))?;
        let size = response.json().and_then(|v| v["content"]["size"].as_u64()).unwrap_or(0);
        self.meta.entries.insert(rel.to_string(), Entry { sha: sha.clone(), size });
        Some(sha)
    }

    fn refuse_uploads(&mut self) -> Upload {
        self.uploads_refused = true;
        let notice = "GitHub refused uploads; your edits stay on this computer. (A token that may only read can't upload: give it Contents read and write, or turn off uploads.)".to_string();
        if !self.notices.contains(&notice) {
            self.notices.push(notice.clone());
        }
        Upload::Refused(notice)
    }
}

fn same_json(a: &str, b: &str) -> bool {
    match (serde_json::from_str::<Value>(a), serde_json::from_str::<Value>(b)) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

/// A status without its times, to tell a real change from a heartbeat.
pub fn status_fingerprint(text: &str) -> Option<Value> {
    let mut value: Value = serde_json::from_str(text).ok()?;
    let object = value.as_object_mut()?;
    object.remove("writtenAt");
    if let Some(profile) = object.get_mut("profile").and_then(Value::as_object_mut) {
        profile.remove("lastSyncAt");
    }
    Some(value)
}

impl Source for GitHubSource {
    fn open(&mut self) -> Result<(), String> {
        if self.meta.not_before > self.now {
            let message = self.wait_message(self.meta.not_before);
            self.stopped = Some(message.clone());
            self.issue = Some("rate-limited");
            return Err(message);
        }
        let url = format!("{}/git/trees/{}?recursive=1", self.repo.base(&self.api), encode(&self.repo.branch));
        let etag = (!self.meta.etag.is_empty() && !self.meta.entries.is_empty()).then(|| self.meta.etag.clone());
        let response = match self.request("GET", url, "application/vnd.github+json", etag, None, MAX_TREE_BYTES) {
            Ok(r) => r,
            Err(problem) => {
                self.issue.get_or_insert(match problem {
                    Problem::Auth => "auth",
                    Problem::NotFound | Problem::Denied => "not-found",
                    Problem::RateLimited => "rate-limited",
                    _ => "offline",
                });
                let what = self.describe(&problem);
                return Err(match problem {
                    Problem::Timeout | Problem::Network => format!("Can't reach GitHub ({}); using the last good copy.", self.repo.label()),
                    _ => format!("{what} Using the last good copy."),
                });
            }
        };
        if response.status == 304 {
            return Ok(());
        }
        let Ok(tree) = serde_json::from_slice::<Tree>(&response.body) else {
            self.issue = Some("offline");
            return Err("GitHub's listing of the repository couldn't be read; using the last good copy.".into());
        };
        if tree.truncated {
            self.issue = Some("truncated");
            return Err(format!(
                "{} has too many files for GitHub to list at once; keep only the word lists in it. Using the last good copy.",
                self.repo.label()
            ));
        }
        if !tree.tree.iter().any(|e| e.path.starts_with("profiles/")) {
            self.issue = Some("empty");
            return Err(format!(
                "{} (branch {}) has no profiles folder yet. Copy the template into it (docs/GITHUB-SYNC.md). Using the last good copy.",
                self.repo.label(),
                self.repo.branch
            ));
        }
        self.meta.entries = tree
            .tree
            .into_iter()
            .filter(|e| e.kind == "blob" && !e.sha.is_empty() && wanted(&e.path, &self.user, &self.device))
            .map(|e| (e.path, Entry { sha: e.sha, size: e.size.unwrap_or(0) }))
            .collect();
        self.meta.etag = response
            .header("etag")
            .filter(|t| t.len() <= 200 && t.chars().all(|c| c.is_ascii_graphic()))
            .unwrap_or("")
            .to_string();
        self.save_meta();
        Ok(())
    }

    fn issue(&self) -> Option<&'static str> {
        self.issue
    }

    fn revision(&self, rel: &str) -> Option<String> {
        self.meta.entries.get(rel).map(|e| format!("git:{}", e.sha))
    }

    fn read(&mut self, rel: &str, max: u64) -> Fetch {
        let Some(entry) = self.meta.entries.get(rel).cloned() else {
            return Fetch::NotFound;
        };
        if entry.size > max {
            return Fetch::Failed(format!("larger than {} KB", max / 1024));
        }
        match self.blob(&entry.sha, max) {
            Ok(text) => Fetch::Text(text),
            Err(Problem::TooLarge) => Fetch::Failed(format!("larger than {} KB", max / 1024)),
            Err(Problem::Status(415)) => Fetch::Failed("not UTF-8 text".into()),
            Err(p @ (Problem::NotSent(_) | Problem::RateLimited | Problem::Auth)) => Fetch::Deferred(self.describe(&p)),
            Err(p) => Fetch::Failed(self.describe(&p)),
        }
    }

    fn list_json(&mut self, rel_dir: &str) -> Vec<String> {
        let prefix = format!("{}/", rel_dir.trim_end_matches('/'));
        let mut names: Vec<String> = self
            .meta
            .entries
            .keys()
            .filter_map(|p| p.strip_prefix(&prefix))
            .filter(|n| !n.contains('/') && n.ends_with(".json") && !n.starts_with('.'))
            .map(str::to_string)
            .collect();
        names.sort();
        names
    }

    fn uploads(&self) -> bool {
        self.upload
    }

    fn create(&mut self, rel_dir: &str, name: &str, local: &Path) -> Upload {
        if !self.upload || self.uploads_refused || self.stopped.is_some() {
            return Upload::Deferred;
        }
        let text = match read_limited(local, MAX_OP_BYTES) {
            Ok(t) => t,
            Err(e) => return Upload::Failed(format!("{name}: {e}")),
        };
        if serde_json::from_str::<Value>(&text).is_err() {
            return Upload::Failed(format!("{name}: not valid JSON"));
        }
        let rel = format!("{}/{name}", rel_dir.trim_end_matches('/'));
        let different = || Upload::Failed(format!("{name}: a different file with this name is already in the repository"));
        // Already listed (uploaded before, perhaps by a check whose answer was lost).
        if self.meta.entries.contains_key(&rel) {
            return match self.read(&rel, MAX_OP_BYTES) {
                Fetch::Text(there) if same_json(&there, &text) => Upload::Done,
                Fetch::Text(_) => different(),
                Fetch::Deferred(_) => Upload::Deferred,
                Fetch::NotFound | Fetch::Failed(_) => Upload::Failed(format!("{name}: couldn't be compared with the copy in the repository")),
            };
        }
        let id = name.strip_suffix(".json").unwrap_or(name).to_string();
        for attempt in 0..2 {
            if self.puts >= MAX_PUTS_PER_CHECK || self.remaining() < WRITE_MARGIN {
                return Upload::Deferred;
            }
            self.puts += 1;
            let result = self.put(&rel, &text, None, format!("VoiceFlow: add edit {id}"));
            match result {
                Ok(response) => {
                    self.note_written(&rel, &response);
                    return Upload::Done;
                }
                Err(Problem::Denied | Problem::NotFound) => return self.refuse_uploads(),
                Err(Problem::RateLimited | Problem::Auth | Problem::NotSent(_)) => return Upload::Deferred,
                // 422 (already there), 409 (the branch moved), 5xx, a lost
                // answer: settle it by reading the file back.
                Err(_) => {
                    if self.remaining() < WRITE_MARGIN {
                        return Upload::Deferred;
                    }
                    match self.read_back(&rel) {
                        Ok(Some(there)) if same_json(&there, &text) => return Upload::Done,
                        Ok(Some(_)) => return different(),
                        Ok(None) if attempt == 0 => continue,
                        Ok(None) => return Upload::Failed(format!("{name}: GitHub didn't take it; tried again at the next check")),
                        Err(_) => return Upload::Deferred,
                    }
                }
            }
        }
        Upload::Deferred
    }

    fn put_status(&mut self, rel: &str, text: &str) -> Result<(), String> {
        if !self.upload || self.uploads_refused || self.stopped.is_some() || self.remaining() < WRITE_MARGIN {
            return Ok(());
        }
        let fingerprint = status_fingerprint(text).ok_or("the status isn't valid JSON")?;
        let listed = self.meta.entries.get(rel).map(|e| e.sha.clone());
        if let Some(record) = &self.meta.status {
            let unchanged = record.fingerprint == fingerprint
                && listed.as_deref() == Some(record.sha.as_str())
                && self.now - record.written_at < STATUS_HEARTBEAT_SECS;
            if unchanged {
                return Ok(());
            }
        }
        let device = self.device.clone();
        let mut sha = listed;
        for attempt in 0..2 {
            match self.put(rel, text, sha.as_deref(), format!("VoiceFlow: update status {device}")) {
                Ok(response) => {
                    let written = self.note_written(rel, &response).unwrap_or_default();
                    self.meta.status = Some(StatusRecord { sha: written, fingerprint, written_at: self.now });
                    self.save_meta();
                    return Ok(());
                }
                Err(Problem::Denied | Problem::NotFound) => {
                    self.refuse_uploads();
                    return Ok(());
                }
                Err(p @ (Problem::Status(409) | Problem::Status(422))) if attempt == 0 => {
                    if self.remaining() < WRITE_MARGIN {
                        return Err(self.describe(&p));
                    }
                    sha = self.current_sha(rel).map_err(|p| self.describe(&p))?;
                }
                Err(p) => return Err(self.describe(&p)),
            }
        }
        Err("GitHub kept refusing the status file; tried again at the next check".into())
    }

    fn take_messages(&mut self) -> (Vec<String>, Vec<String>) {
        let mut errors = std::mem::take(&mut self.errors);
        // A stop that came after the listing (a rate limit, a refused token)
        // is said once.
        if let Some(why) = &self.stopped {
            if !errors.contains(why) {
                errors.push(why.clone());
            }
        }
        (errors, std::mem::take(&mut self.notices))
    }

    fn finish(&mut self) {
        self.save_meta();
    }
}

// ---------------------------------------------------------------- Test connection

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LayerCheck {
    pub layer: Layer,
    /// `ok`, `missing` or `invalid`.
    pub state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TestReport {
    /// The repository and branch could be read.
    pub ok: bool,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub private: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default_branch: Option<String>,
    /// The branch that was read (the default one when none was given).
    pub branch: String,
    pub users: Vec<String>,
    pub user_found: bool,
    pub layers: Vec<LayerCheck>,
    pub warnings: Vec<String>,
    /// GitHub asked to wait until then (unix seconds): kept with the word
    /// lists' other state, so the next check waits too. Not sent to the page.
    #[serde(skip)]
    pub not_before: Option<i64>,
}

/// Reads the repository, its branch and this user's layers; writes nothing.
/// `branch` empty: the repository's default branch.
pub fn test_connection(http: &dyn Transport, api: &str, token: &str, repo: &str, branch: &str, user: &str, now: i64, not_before: i64) -> TestReport {
    let limited: std::cell::Cell<Option<i64>> = std::cell::Cell::new(None);
    let mut report = run_test(http, api, token, repo, branch, user, now, not_before, &limited);
    report.not_before = limited.get();
    report
}

#[allow(clippy::too_many_arguments)]
fn run_test(
    http: &dyn Transport,
    api: &str,
    token: &str,
    repo: &str,
    branch: &str,
    user: &str,
    now: i64,
    not_before: i64,
    limited: &std::cell::Cell<Option<i64>>,
) -> TestReport {
    let mut report = TestReport::default();
    if not_before > now {
        report.message = format!(
            "GitHub asked VoiceFlow to wait; try again after {} (UTC).",
            iso_from_unix(not_before).replace('T', " ").trim_end_matches('Z')
        );
        return report;
    }
    // The branch is checked after the repository answers (it may be empty).
    let parsed = match Repo::parse(repo, if branch.trim().is_empty() { "main" } else { branch }) {
        Ok(r) => r,
        Err(e) => {
            report.message = e;
            return report;
        }
    };
    let deadline = Instant::now() + Duration::from_secs(25);
    let started = Instant::now();
    // After a rate limit, nothing more is asked.
    let send = |url: String, accept: &'static str, max: usize| -> Result<HttpResponse, String> {
        if limited.get().is_some() {
            return Err("GitHub asked VoiceFlow to slow down; try again later.".into());
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left < Duration::from_secs(1) {
            return Err("GitHub didn't answer in time.".into());
        }
        let request = HttpRequest { method: "GET", url, accept, if_none_match: None, body: None, timeout: left.min(REQUEST_TIMEOUT), max_body: max };
        let response = http.send(&request, token).map_err(|e| match e {
            HttpError::Timeout => "GitHub didn't answer in time.".to_string(),
            HttpError::Network(_) => "Can't reach GitHub. Check the internet connection.".to_string(),
            HttpError::TooLarge => "GitHub's answer was larger than expected.".to_string(),
        })?;
        if (200..300).contains(&response.status) {
            return Ok(response);
        }
        if let Some(until) = rate_limit_until(&response, now.saturating_add(started.elapsed().as_secs() as i64)) {
            limited.set(Some(until));
            return Err("GitHub asked VoiceFlow to slow down; try again later.".into());
        }
        Err(match response.status {
            401 => "GitHub didn't accept the token. Check that it was copied whole and hasn't expired.".to_string(),
            403 => format!("GitHub refused this token access to {}.", parsed.label()),
            404 => format!(
                "GitHub can't find {} with this token. Check the name, and that the token was made for this repository.",
                parsed.label()
            ),
            s => format!("GitHub answered with an error (HTTP {s})."),
        })
    };
    let base = parsed.base(api.trim_end_matches('/'));
    let meta = match send(base.clone(), "application/vnd.github+json", MAX_JSON_BYTES) {
        Ok(r) => r.json().unwrap_or(Value::Null),
        Err(e) => {
            report.message = e;
            return report;
        }
    };
    report.private = meta["private"].as_bool();
    report.default_branch = meta["default_branch"].as_str().map(str::to_string);
    if report.private == Some(false) {
        report.warnings.push("This repository is public: anyone can read its word lists. Make it private in its GitHub settings.".into());
    }
    let branch = if branch.trim().is_empty() {
        report.default_branch.clone().unwrap_or_else(|| "main".into())
    } else {
        branch.trim().to_string()
    };
    if !valid_branch(&branch) {
        report.message = "Enter the branch name (usually main).".into();
        return report;
    }
    report.branch = branch.clone();
    let tree_url = format!("{base}/git/trees/{}?recursive=1", encode(&branch));
    let tree: Tree = match send(tree_url, "application/vnd.github+json", MAX_TREE_BYTES) {
        Ok(r) => match serde_json::from_slice(&r.body) {
            Ok(t) => t,
            Err(_) => {
                report.message = "GitHub's listing of the repository couldn't be read.".into();
                return report;
            }
        },
        Err(e) => {
            report.message = if e.contains("can't find") {
                format!("GitHub can't find the branch {branch} in {}.", parsed.label())
            } else {
                e
            };
            return report;
        }
    };
    if tree.truncated {
        report.message = format!("{} has too many files for GitHub to list at once; keep only the word lists in it.", parsed.label());
        return report;
    }
    report.ok = true;
    report.users = users_in(&tree);
    let user = user.trim().to_lowercase();
    report.user_found = !user.is_empty() && report.users.contains(&user);
    if !tree.tree.iter().any(|e| e.path.starts_with("profiles/")) {
        report.warnings.push("The repository has no profiles folder yet: copy the template into it.".into());
    } else if !user.is_empty() && !report.user_found {
        report.warnings.push(format!("There is no profiles/{user}/ folder yet; VoiceFlow makes one with your first edit, or add your words there."));
    }
    let blobs: BTreeMap<String, (String, u64)> = tree
        .tree
        .iter()
        .filter(|e| e.kind == "blob")
        .map(|e| (e.path.clone(), (e.sha.clone(), e.size.unwrap_or(0))))
        .collect();
    let mut layers = vec![(Layer::Household, "profiles/household/profile.json".to_string())];
    if valid_name(&user) {
        layers.push((Layer::Generated, format!("profiles/{user}/generated/profile.json")));
        layers.push((Layer::Personal, format!("profiles/{user}/profile.json")));
    }
    for (layer, path) in layers {
        let check = match blobs.get(&path) {
            None => LayerCheck { layer, state: "missing".into(), error: None },
            Some((_, size)) if *size > MAX_PROFILE_BYTES => LayerCheck { layer, state: "invalid".into(), error: Some(format!("{path} is larger than 1 MB")) },
            Some((sha, _)) => match send(format!("{base}/git/blobs/{}", encode(sha)), "application/vnd.github.raw+json", MAX_PROFILE_BYTES as usize) {
                Ok(r) => match String::from_utf8(r.body).map_err(|_| "not UTF-8 text".to_string()).and_then(|t| parse_profile(&t, layer, &user).map(|_| ())) {
                    Ok(()) => LayerCheck { layer, state: "ok".into(), error: None },
                    Err(e) => LayerCheck { layer, state: "invalid".into(), error: Some(format!("{path}: {e}")) },
                },
                Err(e) => LayerCheck { layer, state: "invalid".into(), error: Some(e) },
            },
        };
        report.layers.push(check);
    }
    report.message = format!(
        "Connected to {} ({}). Reading works; writing is checked at the first upload.",
        parsed.label(),
        branch
    );
    report
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::sync::{check, refresh_with, Config, Paths};
    use std::fs;
    use std::sync::{Arc, Mutex};

    // ------------------------------------------------------------ a fake GitHub

    /// One branch of one repository, in memory, answering the routes the
    /// source uses the way GitHub does (checked against the real API).
    #[derive(Default)]
    struct State {
        files: BTreeMap<String, String>,
        commits: u64,
        private: bool,
        truncated: bool,
        offline: bool,
        /// Every request answers this status (401, 403, 500…).
        force: Option<(u16, Vec<(String, String)>, String)>,
        /// PUTs answer 403 (a token that may only read).
        read_only: bool,
        /// The next PUT is applied but its answer is lost (a timeout).
        lose_next_put: bool,
        requests: Vec<String>,
        token: String,
    }

    #[derive(Clone)]
    struct Fake(Arc<Mutex<State>>);

    fn sha_of(text: &str) -> String {
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
        for b in text.bytes() {
            hash ^= u64::from(b);
            hash = hash.wrapping_mul(0x0100_0000_01b3);
        }
        format!("{hash:016x}{:024x}", text.len())
    }

    fn reply(status: u16, body: &str) -> HttpResponse {
        HttpResponse { status, headers: Vec::new(), body: body.as_bytes().to_vec() }
    }

    impl Fake {
        fn new() -> Fake {
            Fake(Arc::new(Mutex::new(State { private: true, token: "github_pat_test_token_0000000000".into(), ..State::default() })))
        }
        fn put_file(&self, path: &str, text: &str) {
            let mut s = self.0.lock().unwrap();
            s.files.insert(path.into(), text.into());
            s.commits += 1;
        }
        fn file(&self, path: &str) -> Option<String> {
            self.0.lock().unwrap().files.get(path).cloned()
        }
        fn count(&self, needle: &str) -> usize {
            self.0.lock().unwrap().requests.iter().filter(|r| r.contains(needle)).count()
        }
        fn clear_requests(&self) {
            self.0.lock().unwrap().requests.clear();
        }
        fn set(&self, f: impl FnOnce(&mut State)) {
            f(&mut self.0.lock().unwrap());
        }
    }

    impl Transport for Fake {
        fn send(&self, request: &HttpRequest, token: &str) -> Result<HttpResponse, HttpError> {
            let mut s = self.0.lock().unwrap();
            let path = request.url.strip_prefix("https://fake.test/repos/sam-lee/words").unwrap_or(&request.url).to_string();
            s.requests.push(format!("{} {}{}", request.method, path, if request.if_none_match.is_some() { " (if-none-match)" } else { "" }));
            if s.offline {
                return Err(HttpError::Network("offline".into()));
            }
            if token != s.token {
                return Ok(reply(401, r#"{"message":"Bad credentials"}"#));
            }
            if let Some((status, headers, body)) = &s.force {
                return Ok(HttpResponse { status: *status, headers: headers.clone(), body: body.as_bytes().to_vec() });
            }
            let (route, query) = path.split_once('?').unwrap_or((&path, ""));
            let etag = format!("W/\"tree-{}\"", s.commits);
            if request.method == "GET" && route.is_empty() {
                return Ok(reply(200, &serde_json::json!({ "private": s.private, "default_branch": "main" }).to_string()));
            }
            if request.method == "GET" && route == "/git/trees/main" {
                assert_eq!(query, "recursive=1");
                if request.if_none_match.as_deref().map(|t| t.trim_start_matches("W/")) == Some(etag.trim_start_matches("W/")) {
                    return Ok(HttpResponse { status: 304, headers: vec![("etag".into(), etag)], body: Vec::new() });
                }
                let mut entries = Vec::new();
                let mut dirs = std::collections::BTreeSet::new();
                for (p, text) in &s.files {
                    let parts: Vec<&str> = p.split('/').collect();
                    for i in 1..parts.len() {
                        dirs.insert(parts[..i].join("/"));
                    }
                    entries.push(serde_json::json!({ "path": p, "type": "blob", "mode": "100644", "sha": sha_of(text), "size": text.len() }));
                }
                for d in dirs {
                    entries.push(serde_json::json!({ "path": d, "type": "tree", "mode": "040000", "sha": "t" }));
                }
                let body = serde_json::json!({ "sha": "root", "tree": entries, "truncated": s.truncated }).to_string();
                return Ok(HttpResponse { status: 200, headers: vec![("etag".into(), etag)], body: body.into_bytes() });
            }
            if request.method == "GET" && route.starts_with("/git/trees/") {
                return Ok(reply(404, r#"{"message":"Not Found"}"#));
            }
            if let Some(sha) = route.strip_prefix("/git/blobs/") {
                return Ok(match s.files.values().find(|t| sha_of(t) == sha) {
                    Some(text) => reply(200, text),
                    None => reply(404, r#"{"message":"Not Found"}"#),
                });
            }
            if let Some(file) = route.strip_prefix("/contents/") {
                let file = file.replace("%2F", "/");
                if request.method == "GET" {
                    return Ok(match s.files.get(&file) {
                        Some(text) if request.accept.contains("raw") => reply(200, text),
                        Some(text) => reply(200, &serde_json::json!({ "sha": sha_of(text) }).to_string()),
                        None => reply(404, r#"{"message":"Not Found"}"#),
                    });
                }
                if s.read_only {
                    return Ok(reply(403, r#"{"message":"Resource not accessible by personal access token"}"#));
                }
                let body: Value = serde_json::from_slice(request.body.as_deref().unwrap()).unwrap();
                assert_eq!(body["branch"], "main");
                let text = String::from_utf8(base64::engine::general_purpose::STANDARD.decode(body["content"].as_str().unwrap()).unwrap()).unwrap();
                let current = s.files.get(&file).map(|t| sha_of(t));
                match (body["sha"].as_str(), &current) {
                    (None, Some(_)) => return Ok(reply(422, r#"{"message":"Invalid request.\n\n\"sha\" wasn't supplied."}"#)),
                    (Some(given), Some(cur)) if given != cur => return Ok(reply(409, r#"{"message":"does not match"}"#)),
                    (Some(_), None) => return Ok(reply(409, r#"{"message":"does not match"}"#)),
                    _ => {}
                }
                assert!(body["message"].as_str().unwrap().starts_with("VoiceFlow: "));
                let sha = sha_of(&text);
                let size = text.len();
                s.files.insert(file, text);
                s.commits += 1;
                if s.lose_next_put {
                    s.lose_next_put = false;
                    return Err(HttpError::Timeout);
                }
                return Ok(reply(if current.is_some() { 200 } else { 201 }, &serde_json::json!({ "content": { "sha": sha, "size": size } }).to_string()));
            }
            Ok(reply(404, r#"{"message":"Not Found"}"#))
        }
    }

    // ------------------------------------------------------------ fixtures

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
        now: i64,
        upload: bool,
    }

    fn setup() -> Setup {
        let root = tempfile::tempdir().unwrap();
        let config = Config {
            enabled: true,
            kind: "github".into(),
            repo: "sam-lee/words".into(),
            branch: "main".into(),
            user: "sam".into(),
            device: "dev1".into(),
            ..Config::default()
        };
        let paths = Paths::new(root.path(), &config);
        let fake = Fake::new();
        fake.put_file("README.md", "invented words only");
        fake.put_file("profiles/household/profile.json", HOUSEHOLD);
        fake.put_file("profiles/sam/profile.json", PERSONAL);
        Setup { _root: root, paths, fake, now: 1_795_600_000, upload: true }
    }

    impl Setup {
        fn source(&self) -> GitHubSource {
            GitHubSource::with_api(
                Options {
                    repo: self.paths.github.clone().unwrap(),
                    user: "sam".into(),
                    device: "dev1".into(),
                    token: "github_pat_test_token_0000000000".into(),
                    upload: self.upload,
                    meta_path: self.paths.github_meta(),
                    deadline: Instant::now() + Duration::from_secs(40),
                    now: self.now,
                },
                Box::new(self.fake.clone()),
                "https://fake.test",
            )
        }
        fn check(&self) -> crate::profile::sync::Outcome {
            check(&self.paths, &mut self.source(), "0.9.19", "2026-11-25T10:00:00Z")
        }
        fn local_edit(&self, name: &str, text: &str) {
            let path = self.paths.local_ops().join(name);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, text).unwrap();
        }
    }

    // ------------------------------------------------------------ reading

    #[test]
    fn reads_the_repo_then_costs_one_304_when_nothing_changed() {
        let s = setup();
        let out = s.check();
        assert!(out.report.source_reachable, "{:?}", out.report.errors);
        assert!(out.report.errors.is_empty(), "{:?}", out.report.errors);
        assert_eq!(out.snapshot.terms.len(), 2);
        assert_eq!(out.snapshot.corrections.len(), 1);
        assert_eq!(s.fake.count("/git/blobs/"), 2);
        // The status went up (the first one is always written).
        assert!(s.fake.file("status/sam/dev1.json").is_some());
        s.fake.clear_requests();
        // Nothing changed since this device's own status commit: the listing
        // moved once (that commit), then 304; no file is downloaded again.
        let again = s.check();
        assert_eq!(again.snapshot.id, out.snapshot.id);
        assert_eq!(s.fake.count("/git/blobs/"), 0, "unchanged layers come from the copy");
        assert_eq!(s.fake.count("PUT"), 0, "an unchanged status isn't written again");
        s.fake.clear_requests();
        let third = s.check();
        assert_eq!(third.snapshot.id, out.snapshot.id);
        let requests = s.fake.0.lock().unwrap().requests.clone();
        assert_eq!(requests, vec!["GET /git/trees/main?recursive=1 (if-none-match)".to_string()]);
    }

    #[test]
    fn an_edit_from_another_device_appears_and_is_kept() {
        let s = setup();
        s.check();
        s.fake.put_file("profiles/sam/ops/ph12ab34cd-1.json", &op("ph12ab34cd-1", "ph12ab34cd", r#""type":"term","term":{"text":"Zephlet"}"#));
        let out = s.check();
        assert!(out.snapshot.terms.iter().any(|t| t.text == "Zephlet"));
        // Offline later: still there, from this device's copy.
        let offline = refresh_with(&s.paths, None, "t");
        assert!(offline.snapshot.terms.iter().any(|t| t.text == "Zephlet"));
        assert!(!offline.report.source_reachable);
    }

    #[test]
    fn a_bad_layer_keeps_the_last_good_copy_and_is_not_downloaded_again() {
        let s = setup();
        let good = s.check().snapshot;
        s.fake.put_file("profiles/sam/profile.json", &PERSONAL[..50]);
        let out = s.check();
        assert_eq!(out.snapshot.id, good.id);
        assert_eq!(out.report.layers[2].state, "cached");
        assert!(out.report.errors.iter().any(|e| e.contains("profiles/sam/profile.json was not used")));
        s.fake.clear_requests();
        let again = s.check();
        assert_eq!(again.report.layers[2].state, "cached");
        assert!(again.report.errors.iter().any(|e| e.contains("was not used")), "the problem is still shown");
        assert_eq!(s.fake.count("/git/blobs/"), 0, "the same bad file isn't fetched again");
        // Fixed: read and used.
        s.fake.put_file("profiles/sam/profile.json", &PERSONAL.replace("Quorvane\",\"priority\":80", "Quorvane\",\"priority\":81"));
        let fixed = s.check();
        assert_eq!(fixed.report.layers[2].state, "ok");
    }

    #[test]
    fn a_bad_edit_file_is_not_downloaded_again_until_it_changes() {
        let s = setup();
        s.fake.put_file("profiles/sam/ops/ph1-bad.json", r#"{"schema":"voiceflow-op"}"#);
        let out = s.check();
        assert_eq!(out.snapshot.issues.iter().filter(|i| i.code == "edit-rejected").count(), 1);
        s.fake.clear_requests();
        s.fake.put_file("README.md", "changed, so the listing is read again");
        let again = s.check();
        assert_eq!(again.snapshot.issues.iter().filter(|i| i.code == "edit-rejected").count(), 1, "still reported");
        assert_eq!(s.fake.count("/git/blobs/"), 0, "not downloaded again");
        // Fixed in place: read again and used.
        s.fake.put_file("profiles/sam/ops/ph1-bad.json", &op("ph1-bad", "ph1", r#""type":"term","term":{"text":"Zephlet"}"#));
        let fixed = s.check();
        assert!(fixed.snapshot.terms.iter().any(|t| t.text == "Zephlet"));
        assert!(fixed.snapshot.issues.iter().all(|i| i.code != "edit-rejected"));
    }

    #[test]
    fn test_connection_stops_at_a_rate_limit_and_reports_the_wait() {
        let s = setup();
        s.fake.set(|st| st.force = Some((429, vec![("retry-after".into(), "120".into())], "{}".into())));
        let report = test_connection(&s.fake, "https://fake.test", "github_pat_test_token_0000000000", "sam-lee/words", "main", "sam", s.now, 0);
        assert!(!report.ok);
        assert_eq!(report.not_before, Some(s.now + 120));
        assert_eq!(s.fake.count(""), 1, "nothing asked after the limit");
    }

    #[test]
    fn a_file_over_the_limit_is_not_downloaded() {
        let s = setup();
        s.check();
        let huge = format!("{}{}", &PERSONAL[..PERSONAL.len() - 1], format!(",\"note\":\"{}\"}}", "x".repeat(MAX_PROFILE_BYTES as usize)));
        s.fake.put_file("profiles/sam/profile.json", &huge);
        s.fake.clear_requests();
        let out = s.check();
        assert_eq!(out.report.layers[2].state, "cached");
        assert!(out.report.errors.iter().any(|e| e.contains("larger than 1024 KB")), "{:?}", out.report.errors);
        assert_eq!(s.fake.count("/git/blobs/"), 0);
    }

    #[test]
    fn a_truncated_listing_is_refused() {
        let s = setup();
        let good = s.check().snapshot;
        s.fake.set(|st| st.truncated = true);
        s.fake.put_file("profiles/sam/profile.json", "{}");
        let out = s.check();
        assert!(!out.report.source_reachable);
        assert_eq!(out.report.source_issue.as_deref(), Some("truncated"));
        assert_eq!(out.snapshot.id, good.id);
    }

    #[test]
    fn a_refused_token_reads_differently_from_offline() {
        let s = setup();
        let good = s.check().snapshot;
        s.fake.set(|st| st.token = "github_pat_someone_else_000000000".into());
        s.fake.clear_requests();
        let out = s.check();
        assert!(!out.report.source_reachable);
        assert_eq!(out.report.source_issue.as_deref(), Some("auth"));
        assert!(out.report.errors[0].contains("didn't accept the token"));
        assert_eq!(out.snapshot.id, good.id, "last good copy");
        assert_eq!(s.fake.count("GET"), 1, "one request for the listing, nothing after a 401");
        s.fake.set(|st| st.offline = true);
        let offline = s.check();
        assert_eq!(offline.report.source_issue.as_deref(), Some("offline"));
        assert!(offline.report.errors[0].starts_with("Can't reach GitHub"));
        assert_eq!(offline.snapshot.id, good.id);
    }

    #[test]
    fn a_repo_without_profiles_is_not_reached() {
        let s = setup();
        s.fake.set(|st| st.files.retain(|p, _| !p.starts_with("profiles/")));
        let out = s.check();
        assert!(!out.report.source_reachable);
        assert_eq!(out.report.source_issue.as_deref(), Some("empty"));
    }

    // ------------------------------------------------------------ writing

    #[test]
    fn edits_go_up_once_and_a_lost_answer_is_settled_by_reading_back() {
        let s = setup();
        s.local_edit("dev1-aaa.json", &op("dev1-aaa", "dev1", r#""type":"term","term":{"text":"Ellisande"}"#));
        let out = s.check();
        assert_eq!(out.report.uploaded_now, 1);
        assert_eq!(out.report.pending_edits, 0);
        assert!(s.fake.file("profiles/sam/ops/dev1-aaa.json").is_some());
        // The next edit's answer is lost after GitHub kept it.
        s.local_edit("dev1-bbb.json", &op("dev1-bbb", "dev1", r#""type":"term","term":{"text":"Zephlet"}"#));
        s.fake.set(|st| st.lose_next_put = true);
        let out = s.check();
        assert_eq!(out.report.uploaded_now, 1, "{:?}", out.report.errors);
        assert!(out.report.errors.is_empty(), "{:?}", out.report.errors);
        assert_eq!(s.fake.count("GET /contents/profiles/sam/ops/dev1-bbb.json"), 1, "read back once");
        // Nothing left to send.
        s.fake.clear_requests();
        let again = s.check();
        assert_eq!(again.report.uploaded_now, 0);
        assert_eq!(s.fake.count("PUT /contents/profiles"), 0);
    }

    #[test]
    fn a_different_file_under_the_same_name_is_never_overwritten() {
        let s = setup();
        s.fake.put_file("profiles/sam/ops/dev1-aaa.json", &op("dev1-aaa", "dev1", r#""type":"tombstone","target":"x""#));
        s.local_edit("dev1-aaa.json", &op("dev1-aaa", "dev1", r#""type":"term","term":{"text":"Ellisande"}"#));
        let out = s.check();
        assert!(out.report.errors.iter().any(|e| e.contains("a different file with this name")));
        assert!(s.fake.file("profiles/sam/ops/dev1-aaa.json").unwrap().contains("tombstone"));
        assert_eq!(out.report.pending_edits, 1);
    }

    #[test]
    fn status_is_written_on_change_or_daily_and_survives_a_conflict() {
        let mut s = setup();
        s.check();
        let first = s.fake.file("status/sam/dev1.json").unwrap();
        assert!(!first.contains("Quorvane"), "no words in the status");
        s.fake.clear_requests();
        // Same day, nothing new: not written.
        s.now += 3600;
        s.check();
        assert_eq!(s.fake.count("PUT /contents/status"), 0);
        // Something changed (a new word): written, with the file's sha.
        s.fake.put_file("profiles/household/profile.json", &HOUSEHOLD.replace("Tovo\",\"heardAs\":[\"Toe Vo\"]", "Tovo\""));
        s.check();
        assert_eq!(s.fake.count("PUT /contents/status"), 1);
        // A day later, unchanged: the heartbeat.
        s.fake.clear_requests();
        s.now += 24 * 3600;
        s.check();
        assert_eq!(s.fake.count("PUT /contents/status"), 1);
        // Someone else replaced the status file: the listing shows another
        // blob, so it is written again; a stale sha (409) is fixed once.
        s.fake.clear_requests();
        s.now += 60;
        let stale = s.source();
        s.fake.put_file("status/sam/dev1.json", "{\"edited\":\"by hand\"}");
        let mut stale = stale;
        // The listing this source reads is taken now (it sees the hand edit)...
        stale.open().unwrap();
        // ...then the file changes again before the write.
        s.fake.put_file("status/sam/dev1.json", "{\"edited\":\"twice\"}");
        let report = crate::profile::sync::refresh_with(&s.paths, None, "t").report;
        let text = crate::profile::sync::status_text(&s.paths, "0.9.19", &crate::profile::sync::refresh_with(&s.paths, None, "t").snapshot, &report, None).unwrap();
        stale.put_status("status/sam/dev1.json", &text).unwrap();
        assert_eq!(s.fake.count("PUT /contents/status"), 2, "a 409, then the retry");
        assert!(s.fake.file("status/sam/dev1.json").unwrap().contains("voiceflow-status"));
    }

    #[test]
    fn refused_uploads_are_a_notice_and_the_edits_stay_here() {
        let s = setup();
        s.fake.set(|st| st.read_only = true);
        s.local_edit("dev1-aaa.json", &op("dev1-aaa", "dev1", r#""type":"term","term":{"text":"Ellisande"}"#));
        s.local_edit("dev1-bbb.json", &op("dev1-bbb", "dev1", r#""type":"term","term":{"text":"Zephlet"}"#));
        let out = s.check();
        assert!(out.report.source_reachable);
        assert!(out.report.errors.is_empty(), "{:?}", out.report.errors);
        assert!(out.report.notices.iter().any(|n| n.contains("GitHub refused uploads")));
        assert_eq!(out.report.pending_edits, 2);
        assert_eq!(s.fake.count("PUT"), 1, "one refusal stops the uploads for this check");
        assert_eq!(out.snapshot.terms.iter().filter(|t| t.text == "Zephlet").count(), 1, "still used here");
    }

    #[test]
    fn uploads_off_sends_nothing() {
        let mut s = setup();
        s.upload = false;
        s.local_edit("dev1-aaa.json", &op("dev1-aaa", "dev1", r#""type":"term","term":{"text":"Ellisande"}"#));
        let out = s.check();
        assert_eq!(s.fake.count("PUT"), 0);
        assert_eq!(out.report.pending_edits, 1);
        assert!(out.report.notices.iter().any(|n| n.contains("Uploads are off")));
        assert!(s.fake.file("status/sam/dev1.json").is_none());
    }

    #[test]
    fn a_rate_limit_is_honoured_across_checks() {
        let mut s = setup();
        s.check();
        let reset = s.now + 1800;
        s.fake.set(|st| {
            st.force = Some((403, vec![("x-ratelimit-remaining".into(), "0".into()), ("x-ratelimit-reset".into(), reset.to_string())], r#"{"message":"API rate limit exceeded"}"#.into()));
            st.commits += 1;
        });
        let out = s.check();
        assert_eq!(out.report.source_issue.as_deref(), Some("rate-limited"));
        assert_eq!(load_meta(&s.paths.github_meta()).not_before, reset);
        s.fake.set(|st| st.force = None);
        s.fake.clear_requests();
        s.now += 600;
        let waiting = s.check();
        assert_eq!(s.fake.count(""), 0, "no request before the reset");
        assert!(!waiting.report.source_reachable);
        s.now = reset + 1;
        assert!(s.check().report.source_reachable);
    }

    #[test]
    fn rate_limit_answers() {
        let r = |status, headers: &[(&str, &str)], body: &str| HttpResponse {
            status,
            headers: headers.iter().map(|(a, b)| (a.to_string(), b.to_string())).collect(),
            body: body.as_bytes().to_vec(),
        };
        assert_eq!(rate_limit_until(&r(403, &[], r#"{"message":"Resource not accessible by personal access token"}"#), 100), None);
        assert_eq!(rate_limit_until(&r(403, &[("retry-after", "7200")], ""), 100), Some(7300), "a long wait is honoured in full");
        assert_eq!(rate_limit_until(&r(429, &[], ""), 100), Some(160));
        assert_eq!(rate_limit_until(&r(403, &[], "You have exceeded a secondary rate limit"), 100), Some(160));
        assert_eq!(rate_limit_until(&r(403, &[("x-ratelimit-remaining", "0"), ("x-ratelimit-reset", "500"), ("retry-after", "60")], ""), 100), Some(500));
        assert_eq!(rate_limit_until(&r(403, &[("retry-after", "soon")], ""), 100), Some(160), "malformed: the fallback");
        assert_eq!(rate_limit_until(&r(404, &[], ""), 100), None);
    }

    // ------------------------------------------------------------ names and tokens

    #[test]
    fn repo_branch_and_token_shapes() {
        let r = Repo::parse("https://github.com/Sam-Lee/Words.git", "main").unwrap();
        assert_eq!((r.owner.as_str(), r.name.as_str()), ("Sam-Lee", "Words"));
        assert_eq!(r.identity(), "github:sam-lee/words@main");
        assert_ne!(Repo::parse("sam-lee/words", "Main").unwrap().identity(), r.identity(), "a branch keeps its case");
        assert!(Repo::parse("words", "main").is_err());
        assert!(Repo::parse("sam lee/words", "main").is_err());
        assert!(Repo::parse("sam-lee/words", "").is_err());
        assert!(valid_branch("feature/word-lists"));
        assert!(!valid_branch("a..b") && !valid_branch("x/") && !valid_branch("x y") && !valid_branch(".hidden"));
        assert!(valid_token("github_pat_11ABCDEFG0123456789_abcdefghijklmnop"));
        assert!(valid_token("ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
        assert!(!valid_token("vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"));
        assert!(!valid_token("github_pat_short"));
        assert!(!valid_token("github_pat_abc def ghi jkl mno pqr stu"));
        assert_eq!(encode("feature/x y"), "feature%2Fx%20y");
    }

    #[test]
    fn github_and_folder_word_lists_never_share_a_cache() {
        let s = setup();
        let mut folder = Config { enabled: true, source: "github:sam-lee/words@main".into(), user: "sam".into(), device: "dev1".into(), ..Config::default() };
        let folder_paths = Paths::new(s.paths.app_data.as_path(), &folder);
        assert_ne!(folder_paths.scope_key(), s.paths.scope_key());
        folder.kind = "github".into();
        folder.repo = "SAM-LEE/WORDS".into();
        folder.branch = "main".into();
        assert_eq!(Paths::new(s.paths.app_data.as_path(), &folder).scope_key(), s.paths.scope_key(), "owner/name ignore case");
    }

    #[test]
    fn test_connection_reads_and_never_writes() {
        let s = setup();
        s.fake.set(|st| st.private = false);
        let report = test_connection(&s.fake, "https://fake.test", "github_pat_test_token_0000000000", "sam-lee/words", "", "alex", s.now, 0);
        assert!(report.ok, "{}", report.message);
        assert_eq!(report.branch, "main");
        assert_eq!(report.users, vec!["sam".to_string()]);
        assert!(!report.user_found);
        assert!(report.warnings.iter().any(|w| w.contains("public")));
        assert!(report.warnings.iter().any(|w| w.contains("profiles/alex/")));
        let report = test_connection(&s.fake, "https://fake.test", "github_pat_test_token_0000000000", "sam-lee/words", "main", "sam", s.now, 0);
        assert!(report.user_found);
        assert!(report.layers.iter().all(|l| l.state == "ok" || l.state == "missing"), "{:?}", report.layers);
        assert_eq!(s.fake.count("PUT"), 0);
        let bad = test_connection(&s.fake, "https://fake.test", "github_pat_wrong_token_00000000000", "sam-lee/words", "main", "sam", s.now, 0);
        assert!(!bad.ok && bad.message.contains("didn't accept the token"));
        let waiting = test_connection(&s.fake, "https://fake.test", "x", "sam-lee/words", "main", "sam", s.now, s.now + 60);
        assert!(!waiting.ok && waiting.message.contains("wait"));
    }

    /// The real client is made, used and dropped on a blocking thread inside
    /// a tokio runtime, as in the app (`panic = "abort"` in release builds).
    #[test]
    fn the_real_client_lives_on_a_blocking_thread() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let result = runtime.block_on(async {
            tokio::task::spawn_blocking(|| {
                let transport = ReqwestTransport::new("0.0.0-test").unwrap();
                let request = HttpRequest {
                    method: "GET",
                    url: "https://127.0.0.1:9/repos/x/y".into(),
                    accept: "application/vnd.github+json",
                    if_none_match: None,
                    body: None,
                    timeout: Duration::from_secs(5),
                    max_body: 1024,
                };
                transport.send(&request, "github_pat_test_token_0000000000")
            })
            .await
            .unwrap()
        });
        assert!(matches!(result, Err(HttpError::Network(_)) | Err(HttpError::Timeout)), "{result:?}");
        // Plain http is refused before anything is sent.
        let transport = ReqwestTransport::new("0.0.0-test").unwrap();
        let plain = HttpRequest { method: "GET", url: "http://127.0.0.1:9/".into(), accept: "x", if_none_match: None, body: None, timeout: Duration::from_secs(5), max_body: 10 };
        assert!(transport.send(&plain, "t").is_err());
    }

    // ------------------------------------------------------------ live

    /// Against a real private repository holding only invented words. Run
    /// with `VOICEFLOW_GITHUB_TEST_REPO=<owner/name>` and
    /// `VOICEFLOW_GITHUB_TEST_TOKEN=<token>` (read here, never printed):
    /// `cargo test --lib github::tests::live -- --ignored --nocapture --test-threads=1`.
    /// It refuses to write to a public repository.
    #[test]
    #[ignore = "needs VOICEFLOW_GITHUB_TEST_REPO and VOICEFLOW_GITHUB_TEST_TOKEN"]
    fn live_private_repo() {
        let repo = std::env::var("VOICEFLOW_GITHUB_TEST_REPO").expect("VOICEFLOW_GITHUB_TEST_REPO");
        let token = std::env::var("VOICEFLOW_GITHUB_TEST_TOKEN").expect("VOICEFLOW_GITHUB_TEST_TOKEN");
        let http = ReqwestTransport::new("0.9.19-test").unwrap();
        let tested = test_connection(&http, API, &token, &repo, "main", "sam", sync_now(), 0);
        assert!(tested.ok, "{}", tested.message);
        assert_eq!(tested.private, Some(true), "refusing to write to a public repository");
        assert!(tested.user_found);
        println!("test connection: {} users={:?} layers={:?}", tested.message, tested.users, tested.layers);

        let root = tempfile::tempdir().unwrap();
        let device = format!("live{}", sync_now() % 1_000_000);
        let config = Config { enabled: true, kind: "github".into(), repo: repo.clone(), branch: "main".into(), user: "sam".into(), device: device.clone(), ..Config::default() };
        let paths = Paths::new(root.path(), &config);
        let source = |token: &str, api: &str, now: i64| {
            GitHubSource::with_api(
                Options {
                    repo: paths.github.clone().unwrap(),
                    user: "sam".into(),
                    device: device.clone(),
                    token: token.to_string(),
                    upload: true,
                    meta_path: paths.github_meta(),
                    deadline: Instant::now() + Duration::from_secs(40),
                    now,
                },
                Box::new(ReqwestTransport::new("0.9.19-test").unwrap()),
                api,
            )
        };
        let run = |src: &mut GitHubSource| check(&paths, src, "0.9.19-test", &crate::profile::sync::now_iso());

        // 1. Read, with a new edit made here going up and the status written.
        let id = format!("{device}-{:08x}", sync_now());
        let local = paths.local_ops().join(format!("{id}.json"));
        fs::create_dir_all(local.parent().unwrap()).unwrap();
        fs::write(&local, op(&id, &device, r#""type":"term","term":{"text":"Velmorin"}"#)).unwrap();
        let first = run(&mut source(&token, API, sync_now()));
        println!("first: reachable={} words={} uploaded={} errors={:?} notices={:?}", first.report.source_reachable, first.snapshot.terms.len(), first.report.uploaded_now, first.report.errors, first.report.notices);
        assert!(first.report.source_reachable);
        assert!(first.report.errors.is_empty(), "{:?}", first.report.errors);
        assert_eq!(first.report.uploaded_now, 1);
        assert!(first.snapshot.terms.iter().any(|t| t.text == "Velmorin"));
        let status_sha = load_meta(&paths.github_meta()).status.expect("status written").sha;

        // 2. Read again: one conditional listing (304 after our own commits
        // settle), no file downloads, no writes.
        let second = run(&mut source(&token, API, sync_now()));
        let third = run(&mut source(&token, API, sync_now()));
        assert_eq!(third.snapshot.id, second.snapshot.id);
        assert_eq!(load_meta(&paths.github_meta()).status.unwrap().sha, status_sha, "unchanged status not rewritten");
        println!("second/third: same snapshot {}", third.snapshot.id);

        // 3. Another device's edit appears in the repo (written straight
        // through the API, as a phone or an AI would).
        let other = format!("other1-{:08x}", sync_now());
        let mut writer = source(&token, API, sync_now());
        writer.open().unwrap();
        let other_file = root.path().join("other.json");
        fs::write(&other_file, op(&other, "other1", r#""type":"term","term":{"text":"Quillith"}"#)).unwrap();
        assert_eq!(writer.create("profiles/sam/ops", &format!("{other}.json"), &other_file), Upload::Done);
        // Uploading the same file again is settled by comparing (422 path).
        let mut again = source(&token, API, sync_now());
        again.open().unwrap();
        again.meta.entries.remove(&format!("profiles/sam/ops/{other}.json"));
        assert_eq!(again.create("profiles/sam/ops", &format!("{other}.json"), &other_file), Upload::Done);
        let fourth = run(&mut source(&token, API, sync_now()));
        println!("fourth: words={} issues={:?} errors={:?}", fourth.snapshot.terms.len(), fourth.snapshot.issues.iter().map(|i| &i.code).collect::<Vec<_>>(), fourth.report.errors);
        assert!(fourth.snapshot.terms.iter().any(|t| t.text == "Quillith"), "the new edit file was read");

        // 4. Status conflict: the file changes after the listing was read.
        let mut stale = source(&token, API, sync_now() + 30 * 3600);
        stale.open().unwrap();
        let mut other_writer = source(&token, API, sync_now() + 30 * 3600);
        other_writer.open().unwrap();
        other_writer.put_status(&paths.status_rel(), "{\"schema\":\"voiceflow-status\",\"note\":\"changed elsewhere\"}").unwrap();
        let text = crate::profile::sync::status_text(&paths, "0.9.19-test", &fourth.snapshot, &fourth.report, None).unwrap();
        stale.put_status(&paths.status_rel(), &text).expect("a 409, then the retry with the current sha");
        println!("status conflict settled");

        // 5. A bad token: one request, refused, last good copy.
        let bad = run(&mut source("github_pat_11AAAAAAA0invalidinvalidinvalid_000000", API, sync_now()));
        assert_eq!(bad.report.source_issue.as_deref(), Some("auth"), "{:?}", bad.report.errors);
        assert_eq!(bad.snapshot.terms.len(), fourth.snapshot.terms.len());

        // 6. No network (nothing listens there): offline, last good copy.
        let offline = run(&mut source(&token, "https://127.0.0.1:9", sync_now()));
        assert_eq!(offline.report.source_issue.as_deref(), Some("offline"));
        assert_eq!(offline.snapshot.terms.len(), fourth.snapshot.terms.len());
        println!("bad token and offline: last good copy kept");
    }

    fn sync_now() -> i64 {
        crate::profile::sync::now_unix()
    }
}
