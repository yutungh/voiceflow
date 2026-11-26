//! VoiceFlow word-list profiles: the file formats and their validation.
//!
//! Two formats, both documented in `pack/README.md` with JSON Schemas in
//! `pack/schema/`:
//!
//! - `voiceflow-profile` v1: one layer of a profile (household, generated or
//!   personal), a whole JSON file written by a person or their assistant.
//! - `voiceflow-op` v1: one immutable edit made in the app (a correction, a
//!   word, or a tombstone that removes an earlier edit), one file per edit.
//!
//! A file is accepted whole or not at all: anything malformed rejects the
//! file and the last good copy of that layer stays in use.
//!
//! VoiceFlow 0.9.13 (phase 3): `voiceflow-profile` v2 (shared style fields
//! and pinned styles) and the `style-set` and `default-set` edits are read
//! here. Nothing on this device writes them yet: readers come before
//! writers, because an older app refuses a whole file it can't read
//! (`pack/README.md`, Versions; `UPLOADABLE_OP_TYPES`).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const PROFILE_SCHEMA: &str = "voiceflow-profile";
/// The newest `schemaVersion` this app reads.
pub const PROFILE_VERSION: u64 = 2;
/// Every `schemaVersion` this app reads (reported in the status file).
pub const PROFILE_VERSIONS_READ: &[u64] = &[1, 2];
pub const OP_SCHEMA: &str = "voiceflow-op";
pub const OP_VERSION: u64 = 1;
/// Every edit type this app reads (reported in the status file).
pub const OP_TYPES_READ: &[&str] = &["correction", "term", "app-style", "style-set", "default-set", "tombstone"];
/// Edit types this app puts on the share. `app-style` (made here by
/// approved feedback fixes), `style-set` and `default-set` are left out
/// until every device of the user reads them (the status files' `reads`,
/// checked with `readers_ready`): Android 0.8.2 refuses a file it can't
/// read whole, so sharing one early would split the devices. Until then
/// such an edit works on this PC only. No code here writes `style-set` or
/// `default-set` at all yet.
pub const UPLOADABLE_OP_TYPES: &[&str] = &["correction", "term", "tombstone"];
/// VoiceFlow 0.9.21: an app rule's style may name a built-in
/// (`vf-builtin-<key>`) or a style made on a PC (`vf-pc-…`), looked up after
/// the word lists, and a device without that style starts in its default
/// (`pack/README.md`, Style references in app rules).
pub const APP_STYLE_REFS_FEATURE: &str = "app-style-device-refs-v1";
/// What this app reads beyond the edit types (reported in the status file's
/// `reads.features`). A device that reports `app-style` without
/// `APP_STYLE_REFS_FEATURE` doesn't count as ready for app-style edits.
pub const READ_FEATURES: &[&str] = &[APP_STYLE_REFS_FEATURE];

/// Tidy-up providers a style may name a model for (`models`).
pub const STYLE_PROVIDERS: &[&str] = &["openai", "anthropic", "google", "groq", "openrouter", "xai", "custom"];
const MAX_ICON: usize = 16;
const MAX_GUIDANCE: usize = 2000;
const MAX_MODEL: usize = 100;
const MAX_PINS: usize = 12;

/// "Applies in every app."
pub const EVERY_APP: &str = "*";

/// Kinds a word may have. Only a hint for people and assistants.
pub const TERM_KINDS: &[&str] = &[
    "person", "bot", "project", "org", "product", "acronym", "place", "term",
];

const MAX_WORD: usize = 60;
const MAX_GLOSS: usize = 80;
const MAX_PROMPT: usize = 8000;
const MAX_ENTRIES: usize = 2000;
/// Heard forms per word or correction, apps per scope, title patterns, ...
const MAX_LIST: usize = 50;
const MAX_NOTE: usize = 500;
const MAX_PATTERN: usize = 200;

/// A character that would break the one-line vocabulary prompt or hide
/// text: control characters and Unicode line/paragraph separators.
fn breaks_line(c: char) -> bool {
    c.is_control() || matches!(c, '\u{2028}' | '\u{2029}' | '\u{200b}'..='\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}' | '\u{feff}')
}

/// Layers in precedence order: a later layer wins over an earlier one.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Layer {
    /// Built into the app.
    Default,
    /// Shared by everyone in the house.
    Household,
    /// Written by a generator (for example from the user's notes).
    Generated,
    /// The user's own file.
    Personal,
    /// Edits made in the app (operation files).
    Edit,
}

impl Layer {
    pub fn as_str(self) -> &'static str {
        match self {
            Layer::Default => "default",
            Layer::Household => "household",
            Layer::Generated => "generated",
            Layer::Personal => "personal",
            Layer::Edit => "edit",
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Scope {
    pub apps: Vec<String>,
}

impl Scope {
    pub fn everywhere() -> Self {
        Scope {
            apps: vec![EVERY_APP.to_string()],
        }
    }
    pub fn is_everywhere(&self) -> bool {
        self.apps.iter().any(|a| a == EVERY_APP)
    }
    pub fn overlaps(&self, other: &Scope) -> bool {
        self.is_everywhere()
            || other.is_everywhere()
            || self.apps.iter().any(|a| other.apps.contains(a))
    }
    /// Whether this scope covers every app the other one covers.
    pub fn covers(&self, other: &Scope) -> bool {
        self.is_everywhere() || (!other.is_everywhere() && other.apps.iter().all(|a| self.apps.contains(a)))
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Term {
    pub id: String,
    pub text: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub heard_as: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gloss: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub priority: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apps: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Correction {
    pub id: String,
    pub from: Vec<String>,
    pub to: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scope: Option<Scope>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Style {
    pub id: String,
    pub label: String,
    pub prompt: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    /// v2: a model per tidy-up provider (`STYLE_PROVIDERS`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub models: Option<BTreeMap<String, String>>,
    /// The style's default level: 0 (Reserved) to 4 (Expressive), as on Android.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expression: Option<u8>,
    /// v2: added after the expression guidance (Android's per-style override).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expression_override: Option<String>,
    /// v2: a short icon (an emoji or a few characters).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    /// v2: which shared expression guidance it follows (casual, business, ...).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    /// v2: the style's voice in a sentence or two (shown as its description).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub guidance: Option<String>,
    /// v2: not offered in pickers; still works where a default or app rule names it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hidden: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

/// The style fields only version 2 has (a version 1 file may use none).
pub const STYLE_V2_FIELDS: &[&str] = &["models", "expressionOverride", "icon", "kind", "guidance", "hidden"];

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Defaults {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transcription_model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub language: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cleanup_model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cleanup_style: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cleanup_timeout_s: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub prompt_chars: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub prompt_terms: Option<u32>,
    /// v2: the styles pinned to the bar, in order (one list, highest layer).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pinned_styles: Option<Vec<String>>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Privacy {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub block_apps: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub title_deny_re: Vec<String>,
}

/// One layer file (`voiceflow-profile` v1).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileFile {
    #[serde(rename = "$schema", default, skip_serializing_if = "Option::is_none")]
    pub json_schema: Option<String>,
    pub schema: String,
    pub schema_version: u64,
    pub layer: String,
    pub user: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub updated_by: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    #[serde(default)]
    pub terms: Vec<Term>,
    #[serde(default)]
    pub corrections: Vec<Correction>,
    #[serde(default)]
    pub styles: Vec<Style>,
    #[serde(default)]
    pub defaults: Defaults,
    #[serde(default)]
    pub privacy: Privacy,
    /// Per-app rules: reserved. Each needs an `id`; the rest is kept as is.
    #[serde(default)]
    pub apps: Vec<Value>,
}

/// The body of a `term` operation ("Add a word").
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TermBody {
    pub text: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub heard_as: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gloss: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub priority: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apps: Option<Vec<String>>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum OpBody {
    Correction {
        from: Vec<String>,
        to: String,
        scope: Scope,
        note: Option<String>,
    },
    Term(TermBody),
    /// VoiceFlow phase F: an app's style and/or expression ("for this app,
    /// use the Casual style" / "less expressive in Kite"), from an approved
    /// feedback fix. It sets the app rule's fields over the word lists'.
    AppStyle {
        app: String,
        style: Option<String>,
        expression: Option<u8>,
    },
    /// 0.9.13 (read only): some fields of one style. Field by field, the
    /// newest edit wins; a style no layer has is created once its edits
    /// give it a label and a prompt.
    StyleSet {
        style: String,
        set: StylePatch,
    },
    /// 0.9.13 (read only): the default style and/or the pinned styles.
    /// `None`: not touched; `Some(None)`: the edits' value is taken away.
    DefaultSet {
        cleanup_style: Option<Option<String>>,
        pinned_styles: Option<Option<Vec<String>>>,
    },
    Tombstone {
        target: String,
    },
}

impl OpBody {
    /// The `type` as written in the file.
    pub fn type_name(&self) -> &'static str {
        match self {
            OpBody::Correction { .. } => "correction",
            OpBody::Term(_) => "term",
            OpBody::AppStyle { .. } => "app-style",
            OpBody::StyleSet { .. } => "style-set",
            OpBody::DefaultSet { .. } => "default-set",
            OpBody::Tombstone { .. } => "tombstone",
        }
    }
}

/// The fields a `style-set` edit sets. For each optional field: `None` is
/// "not touched", `Some(None)` is "cleared" (null in the file).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct StylePatch {
    pub label: Option<String>,
    pub prompt: Option<String>,
    pub model: Option<Option<String>>,
    pub models: Option<Option<BTreeMap<String, String>>>,
    pub expression: Option<Option<u8>>,
    pub expression_override: Option<Option<String>>,
    pub icon: Option<Option<String>>,
    pub kind: Option<Option<String>>,
    pub guidance: Option<Option<String>>,
    pub hidden: Option<Option<bool>>,
    pub note: Option<Option<String>>,
}

/// One edit made in the app (`voiceflow-op` v1).
#[derive(Clone, Debug, PartialEq)]
pub struct Op {
    pub id: String,
    pub device: String,
    pub created_at: String,
    pub body: OpBody,
}

/// Stable ids: lower-case letters, digits, `.`, `_`, `-`; up to 100.
pub fn valid_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 100
        && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
        && bytes
            .iter()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'_' | b'-'))
}

/// User and device names: lower-case letters, digits, `_` and `-`; up to 32.
pub fn valid_name(name: &str) -> bool {
    let bytes = name.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 32
        && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
        && bytes
            .iter()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'_' | b'-'))
}

/// Same form, ignoring case and runs of spaces (as `ops.ts` `sameForm`).
pub fn norm(form: &str) -> String {
    form.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

/// A word or heard form: one line, no angle brackets, not too long.
fn check_word(what: &str, value: &str) -> Result<(), String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Err(format!("{what} is empty"));
    }
    if trimmed.chars().count() > MAX_WORD {
        return Err(format!("{what} “{trimmed}” is longer than {MAX_WORD} characters"));
    }
    if trimmed.contains(['<', '>']) || trimmed.chars().any(breaks_line) {
        return Err(format!("{what} “{trimmed}” contains <, >, a line break or a control character"));
    }
    Ok(())
}

fn check_list(what: &str, len: usize) -> Result<(), String> {
    if len > MAX_LIST {
        return Err(format!("{what}: more than {MAX_LIST} entries"));
    }
    Ok(())
}

fn check_note(what: &str, note: &Option<String>) -> Result<(), String> {
    match note {
        Some(n) if n.chars().count() > MAX_NOTE => Err(format!("{what}: note is longer than {MAX_NOTE} characters")),
        _ => Ok(()),
    }
}

fn check_apps(what: &str, apps: &[String]) -> Result<(), String> {
    check_list(what, apps.len())?;
    if apps.is_empty() {
        return Err(format!("{what}: the app list is empty (use [\"*\"] for every app)"));
    }
    for app in apps {
        let ok = app == EVERY_APP
            || (!app.is_empty()
                && app.len() <= 200
                && app == &app.to_lowercase()
                && !app.contains(['\r', '\n', '<', '>']));
        if !ok {
            return Err(format!("{what}: app “{app}” must be \"*\" or a lower-case app id"));
        }
    }
    Ok(())
}

fn check_term_fields(
    what: &str,
    text: &str,
    heard_as: &[String],
    kind: &Option<String>,
    gloss: &Option<String>,
    priority: &Option<i64>,
    apps: &Option<Vec<String>>,
) -> Result<(), String> {
    check_word(what, text)?;
    check_list(&format!("{what} heardAs"), heard_as.len())?;
    for heard in heard_as {
        check_word(&format!("{what} heardAs"), heard)?;
    }
    if let Some(kind) = kind {
        if !TERM_KINDS.contains(&kind.as_str()) {
            return Err(format!(
                "{what}: kind “{kind}” is not one of {}",
                TERM_KINDS.join(", ")
            ));
        }
    }
    if let Some(gloss) = gloss {
        if gloss.chars().count() > MAX_GLOSS || gloss.contains(['<', '>']) || gloss.chars().any(breaks_line) {
            return Err(format!(
                "{what}: gloss must be one line of at most {MAX_GLOSS} characters without < or >"
            ));
        }
    }
    if let Some(p) = priority {
        if !(0..=100).contains(p) {
            return Err(format!("{what}: priority {p} is outside 0–100"));
        }
    }
    if let Some(apps) = apps {
        check_apps(what, apps)?;
    }
    Ok(())
}

fn check_correction(what: &str, from: &[String], to: &str, scope: &Scope) -> Result<(), String> {
    if from.is_empty() {
        return Err(format!("{what}: `from` is empty"));
    }
    check_list(&format!("{what} from"), from.len())?;
    for f in from {
        check_word(&format!("{what} from"), f)?;
    }
    check_word(&format!("{what} to"), to)?;
    for (i, f) in from.iter().enumerate() {
        if from[..i].iter().any(|g| norm(g) == norm(f)) {
            return Err(format!("{what}: “{}” is listed twice", f.trim()));
        }
    }
    if from.iter().all(|f| f.trim() == to.trim()) {
        return Err(format!("{what}: writes “{}” as itself", to.trim()));
    }
    check_apps(what, &scope.apps)
}

/// Parses and checks one layer file. `layer` and `user` are what the reader
/// expects: a file that names another layer or user is refused, so a file in
/// the wrong place never overrides someone else's words.
pub fn parse_profile(text: &str, layer: Layer, user: &str) -> Result<ProfileFile, String> {
    let value: Value = serde_json::from_str(text.trim_start_matches('\u{feff}'))
        .map_err(|e| format!("not valid JSON ({e})"))?;
    let obj = value.as_object().ok_or("the file is not a JSON object")?;
    match obj.get("schema").and_then(Value::as_str) {
        Some(PROFILE_SCHEMA) => {}
        Some(other) => return Err(format!("schema is “{other}”, expected “{PROFILE_SCHEMA}”")),
        None => return Err(format!("missing \"schema\": \"{PROFILE_SCHEMA}\"")),
    }
    let version = match obj.get("schemaVersion").and_then(Value::as_u64) {
        Some(v) if PROFILE_VERSIONS_READ.contains(&v) => v,
        Some(v) if v > PROFILE_VERSION => {
            return Err(format!(
                "schemaVersion {v} is newer than this VoiceLnq understands ({PROFILE_VERSION}); update VoiceLnq"
            ))
        }
        _ => return Err("schemaVersion must be 1 or 2".to_string()),
    };
    // A version 1 file is read exactly as version 1 readers read it: a
    // version 2 field there (even one set to null) would be refused by every
    // older app, so it is refused here too, with the fix. Checked on the
    // file's own keys, before null and absent look the same.
    if version == 1 {
        let mut used: Vec<String> = Vec::new();
        for s in obj.get("styles").and_then(Value::as_array).into_iter().flatten() {
            let Some(s) = s.as_object() else { continue };
            let id = s.get("id").and_then(Value::as_str).unwrap_or("?");
            for f in STYLE_V2_FIELDS {
                if s.contains_key(*f) {
                    used.push(format!("styles[{id}].{f}"));
                }
            }
        }
        if obj.get("defaults").and_then(Value::as_object).is_some_and(|d| d.contains_key("pinnedStyles")) {
            used.push("defaults.pinnedStyles".to_string());
        }
        if let Some(first) = used.first() {
            return Err(format!(
                "uses {first}, a schemaVersion 2 field, but says schemaVersion 1; set \"schemaVersion\": 2 (older VoiceLnq apps don't read it)"
            ));
        }
    }
    let file: ProfileFile = serde_json::from_value(value)
        .map_err(|e| format!("does not match voiceflow-profile v{version} ({e})"))?;
    if file.layer != layer.as_str() {
        return Err(format!(
            "says it is the “{}” layer but sits where the “{}” layer belongs",
            file.layer,
            layer.as_str()
        ));
    }
    let expected_user = if layer == Layer::Household { "household" } else { user };
    if file.user != expected_user {
        return Err(format!(
            "belongs to “{}”, but this is “{}”'s profile",
            file.user, expected_user
        ));
    }
    validate_profile(&file)?;
    Ok(file)
}

fn check_unique<'a>(what: &str, ids: impl Iterator<Item = &'a str>) -> Result<(), String> {
    let mut seen = std::collections::HashSet::new();
    for id in ids {
        if !valid_id(id) {
            return Err(format!(
                "{what} id “{id}” must be lower-case letters, digits, '.', '_' or '-'"
            ));
        }
        if !seen.insert(id) {
            return Err(format!("{what} id “{id}” is used twice"));
        }
    }
    Ok(())
}

/// The checks inside one file, after it parsed.
pub fn validate_profile(file: &ProfileFile) -> Result<(), String> {
    if file.terms.len() + file.corrections.len() + file.styles.len() > MAX_ENTRIES {
        return Err(format!("more than {MAX_ENTRIES} entries"));
    }
    check_unique("word", file.terms.iter().map(|t| t.id.as_str()))?;
    check_unique("correction", file.corrections.iter().map(|c| c.id.as_str()))?;
    check_unique("style", file.styles.iter().map(|s| s.id.as_str()))?;
    check_note("the file", &file.note)?;
    for t in &file.terms {
        check_note(&format!("word “{}”", t.id), &t.note)?;
        check_term_fields(
            &format!("word “{}”", t.id),
            &t.text,
            &t.heard_as,
            &t.kind,
            &t.gloss,
            &t.priority,
            &t.apps,
        )?;
    }
    for c in &file.corrections {
        check_note(&format!("correction “{}”", c.id), &c.note)?;
        check_correction(
            &format!("correction “{}”", c.id),
            &c.from,
            &c.to,
            c.scope.as_ref().unwrap_or(&Scope::everywhere()),
        )?;
    }
    // Inside one file a heard form is written one way per app, and no
    // correction's result is changed again by another (chains and cycles).
    for (i, a) in file.corrections.iter().enumerate() {
        let sa = a.scope.clone().unwrap_or_else(Scope::everywhere);
        for b in &file.corrections[i + 1..] {
            let sb = b.scope.clone().unwrap_or_else(Scope::everywhere);
            if !sa.overlaps(&sb) {
                continue;
            }
            for f in &a.from {
                if b.from.iter().any(|g| norm(g) == norm(f)) && a.to != b.to {
                    return Err(format!(
                        "“{}” is written as “{}” (correction “{}”) and “{}” (correction “{}”)",
                        f.trim(),
                        a.to,
                        a.id,
                        b.to,
                        b.id
                    ));
                }
            }
        }
        for b in &file.corrections {
            if a.id == b.id {
                continue;
            }
            let sb = b.scope.clone().unwrap_or_else(Scope::everywhere);
            if sa.overlaps(&sb) && b.from.iter().any(|g| norm(g) == norm(&a.to)) && b.to != a.to {
                return Err(format!(
                    "correction “{}” writes “{}”, which correction “{}” changes again to “{}” (chains are not allowed)",
                    a.id, a.to, b.id, b.to
                ));
            }
        }
    }
    for s in &file.styles {
        check_note(&format!("style “{}”", s.id), &s.note)?;
        if s.label.trim().is_empty() || s.label.chars().count() > 40 || s.label.chars().any(breaks_line) {
            return Err(format!("style “{}”: label must be 1–40 characters", s.id));
        }
        if s.prompt.trim().is_empty() || s.prompt.chars().count() > MAX_PROMPT {
            return Err(format!("style “{}”: prompt must be 1–{MAX_PROMPT} characters", s.id));
        }
        if let Some(e) = s.expression {
            if e > 4 {
                return Err(format!("style “{}”: expression must be 0–4", s.id));
            }
        }
        // `model` is left as version 1 read it (any string).
        check_style_extras(
            &format!("style “{}”", s.id),
            None,
            s.models.as_ref(),
            s.expression_override.as_deref(),
            s.icon.as_deref(),
            s.kind.as_deref(),
            s.guidance.as_deref(),
        )?;
    }
    if let Some(pins) = &file.defaults.pinned_styles {
        check_pins("defaults.pinnedStyles", pins)?;
    }
    let d = &file.defaults;
    if let Some(v) = d.prompt_chars {
        if !(50..=4000).contains(&v) {
            return Err("defaults.promptChars must be 50–4000".into());
        }
    }
    if let Some(v) = d.prompt_terms {
        if !(1..=500).contains(&v) {
            return Err("defaults.promptTerms must be 1–500".into());
        }
    }
    if let Some(v) = d.cleanup_timeout_s {
        if !(1..=120).contains(&v) {
            return Err("defaults.cleanupTimeoutS must be 1–120".into());
        }
    }
    if let Some(style) = &d.cleanup_style {
        if style != "raw" && !valid_id(style) {
            return Err(format!("defaults.cleanupStyle “{style}” is not a style id"));
        }
    }
    for model in [&d.transcription_model, &d.cleanup_model, &d.language].into_iter().flatten() {
        if model.trim().is_empty() || model.len() > 100 || model.contains(['\r', '\n']) {
            return Err(format!("defaults: “{model}” is not a valid value"));
        }
    }
    check_list("privacy.blockApps", file.privacy.block_apps.len())?;
    check_list("privacy.titleDenyRe", file.privacy.title_deny_re.len())?;
    for app in &file.privacy.block_apps {
        if app.trim().is_empty() || app != &app.to_lowercase() {
            return Err(format!("privacy.blockApps: “{app}” must be a lower-case app id"));
        }
    }
    for re in &file.privacy.title_deny_re {
        if re.chars().count() > MAX_PATTERN {
            return Err(format!("privacy.titleDenyRe: a pattern is longer than {MAX_PATTERN} characters"));
        }
        title_pattern(re).map_err(|_| format!("privacy.titleDenyRe: “{re}” is not a valid pattern"))?;
    }
    let mut app_ids = std::collections::HashSet::new();
    for app in &file.apps {
        let id = app
            .get("id")
            .and_then(Value::as_str)
            .ok_or("every entry in apps needs an \"id\"")?;
        if !valid_id(id) || !app_ids.insert(id.to_string()) {
            return Err(format!("apps: id “{id}” is invalid or used twice"));
        }
        // Read since 0.9.7 (an app rule's starting level).
        match app.get("expression") {
            None | Some(Value::Null) => {}
            Some(v) if v.as_u64().is_some_and(|e| e <= 4) => {}
            Some(_) => return Err(format!("apps: “{id}” expression must be 0–4")),
        }
    }
    Ok(())
}

/// One line, no angle brackets or control characters.
fn one_line(text: &str) -> bool {
    !text.contains(['<', '>']) && !text.chars().any(breaks_line)
}

/// The version 2 style fields (and `model`), wherever they come from: a
/// layer file or a `style-set` edit.
fn check_style_extras(
    what: &str,
    model: Option<&str>,
    models: Option<&BTreeMap<String, String>>,
    expression_override: Option<&str>,
    icon: Option<&str>,
    kind: Option<&str>,
    guidance: Option<&str>,
) -> Result<(), String> {
    if let Some(m) = model {
        check_model_name(what, m)?;
    }
    if let Some(models) = models {
        for (provider, m) in models {
            if !STYLE_PROVIDERS.contains(&provider.as_str()) {
                return Err(format!(
                    "{what}: models has “{provider}”, not one of {}",
                    STYLE_PROVIDERS.join(", ")
                ));
            }
            check_model_name(what, m)?;
        }
    }
    if let Some(text) = expression_override {
        if text.chars().count() > MAX_GUIDANCE {
            return Err(format!("{what}: expressionOverride is longer than {MAX_GUIDANCE} characters"));
        }
    }
    if let Some(text) = guidance {
        if text.chars().count() > MAX_GUIDANCE {
            return Err(format!("{what}: guidance is longer than {MAX_GUIDANCE} characters"));
        }
    }
    if let Some(icon) = icon {
        let n = icon.chars().count();
        // An emoji sequence may join with ZWJ; only line breaks and control
        // characters are refused.
        if n == 0 || n > MAX_ICON || icon.contains(['<', '>']) || icon.chars().any(|c| c.is_control() || matches!(c, '\u{2028}' | '\u{2029}')) {
            return Err(format!("{what}: icon must be 1–{MAX_ICON} characters on one line"));
        }
    }
    if let Some(kind) = kind {
        if !valid_kind(kind) {
            return Err(format!(
                "{what}: kind “{kind}” is not casual, business, family, partner, custom or fun_<name>"
            ));
        }
    }
    Ok(())
}

fn check_model_name(what: &str, model: &str) -> Result<(), String> {
    if model.trim().is_empty() || model.chars().count() > MAX_MODEL || !one_line(model) {
        return Err(format!("{what}: a model name must be one line of 1–{MAX_MODEL} characters"));
    }
    Ok(())
}

/// `casual`, `business`, `family`, `partner`, `custom` or `fun_<a-z>`.
pub fn valid_kind(kind: &str) -> bool {
    matches!(kind, "casual" | "business" | "family" | "partner" | "custom")
        || kind.strip_prefix("fun_").is_some_and(|rest| {
            !rest.is_empty() && rest.len() <= 30 && rest.bytes().all(|b| b.is_ascii_lowercase())
        })
}

fn check_pins(what: &str, pins: &[String]) -> Result<(), String> {
    if pins.len() > MAX_PINS {
        return Err(format!("{what}: more than {MAX_PINS} styles"));
    }
    if let Some(bad) = pins.iter().find(|p| !valid_id(p)) {
        return Err(format!("{what}: “{bad}” is not a style id"));
    }
    Ok(())
}

/// A window-title pattern, matched case-insensitively by this app (in Rust,
/// in linear time: a pattern can't stall the app whatever it contains).
pub fn title_pattern(source: &str) -> Result<regex::Regex, regex::Error> {
    regex::RegexBuilder::new(source)
        .case_insensitive(true)
        .size_limit(1 << 20)
        .build()
}

fn str_field<'a>(v: &'a serde_json::Map<String, Value>, key: &str) -> Result<&'a str, String> {
    v.get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| format!("missing \"{key}\""))
}

fn string_list(v: Option<&Value>) -> Option<Vec<String>> {
    let arr = v?.as_array()?;
    arr.iter().map(|x| x.as_str().map(str::to_string)).collect()
}

/// Parses and checks one operation file. `file_stem` is its file name
/// without `.json`, which must equal its id.
pub fn parse_op(text: &str, file_stem: Option<&str>) -> Result<Op, String> {
    let value: Value = serde_json::from_str(text.trim_start_matches('\u{feff}'))
        .map_err(|e| format!("not valid JSON ({e})"))?;
    let v = value.as_object().ok_or("not a JSON object")?;
    if v.get("schema").and_then(Value::as_str) != Some(OP_SCHEMA) {
        return Err(format!("schema must be “{OP_SCHEMA}”"));
    }
    match v.get("version").and_then(Value::as_u64) {
        Some(OP_VERSION) => {}
        Some(n) if n > OP_VERSION => return Err(format!("version {n} is newer than this VoiceLnq understands")),
        _ => return Err(format!("version must be {OP_VERSION}")),
    }
    let id = str_field(v, "id")?.to_string();
    let device = str_field(v, "device")?.to_string();
    let created_at = str_field(v, "createdAt")?.to_string();
    if !valid_name(&device) {
        return Err(format!("device “{device}” is not a valid device name"));
    }
    if !valid_id(&id) || !id.starts_with(&format!("{device}-")) {
        return Err(format!("id “{id}” must be “{device}-<uuid>”"));
    }
    if let Some(stem) = file_stem {
        if stem != id {
            return Err(format!("file name “{stem}.json” does not match id “{id}”"));
        }
    }
    if !looks_like_iso_time(&created_at) {
        return Err(format!("createdAt “{created_at}” is not an ISO 8601 time"));
    }
    let what = format!("edit “{id}”");
    let body = match v.get("type").and_then(Value::as_str) {
        Some("tombstone") => {
            let target = str_field(v, "target")?.to_string();
            if !valid_id(&target) || target == id {
                return Err(format!("{what}: target “{target}” is not another edit's id"));
            }
            OpBody::Tombstone { target }
        }
        Some("correction") => {
            let from: Vec<String> = string_list(v.get("from"))
                .ok_or(format!("{what}: `from` must be a list of words"))?
                .into_iter()
                .map(|f| f.trim().to_string())
                .filter(|f| !f.is_empty())
                .collect();
            let to = str_field(v, "to")?.trim().to_string();
            let apps = v
                .get("scope")
                .and_then(|s| string_list(s.get("apps")))
                .ok_or(format!("{what}: needs scope.apps"))?
                .into_iter()
                .take(MAX_LIST + 1)
                .map(|a| if a == EVERY_APP { a } else { a.to_lowercase() })
                .collect();
            let scope = Scope { apps };
            check_correction(&what, &from, &to, &scope)?;
            let note = v
                .get("note")
                .and_then(Value::as_str)
                .map(|n| n.trim().to_string())
                .filter(|n| !n.is_empty());
            check_note(&what, &note)?;
            OpBody::Correction { from, to, scope, note }
        }
        Some("term") => {
            let term: TermBody = serde_json::from_value(
                v.get("term").cloned().ok_or(format!("{what}: needs a \"term\""))?,
            )
            .map_err(|e| format!("{what}: {e}"))?;
            check_term_fields(
                &what,
                &term.text,
                &term.heard_as,
                &term.kind,
                &term.gloss,
                &term.priority,
                &term.apps,
            )?;
            OpBody::Term(TermBody {
                text: term.text.trim().to_string(),
                heard_as: term.heard_as.iter().map(|h| h.trim().to_string()).collect(),
                ..term
            })
        }
        Some("app-style") => {
            let app = str_field(v, "app")?.trim().to_lowercase();
            if !valid_id(&app) {
                return Err(format!("{what}: app “{app}” is not a valid app id"));
            }
            let style = match v.get("style") {
                None | Some(Value::Null) => None,
                Some(Value::String(s)) if s == "raw" || valid_id(s) => Some(s.clone()),
                Some(_) => return Err(format!("{what}: style must be a style id or \"raw\"")),
            };
            let expression = match v.get("expression") {
                None | Some(Value::Null) => None,
                Some(n) => match n.as_u64() {
                    Some(e) if e <= 4 => Some(e as u8),
                    _ => return Err(format!("{what}: expression must be 0 to 4")),
                },
            };
            if style.is_none() && expression.is_none() {
                return Err(format!("{what}: needs a style or an expression"));
            }
            OpBody::AppStyle { app, style, expression }
        }
        Some("style-set") => {
            let style = str_field(v, "style")?.to_string();
            if !valid_id(&style) || style == "raw" {
                return Err(format!("{what}: style “{style}” is not a style id"));
            }
            let set = v
                .get("set")
                .and_then(Value::as_object)
                .ok_or(format!("{what}: needs a \"set\" object"))?;
            OpBody::StyleSet {
                style,
                set: parse_style_patch(&what, set)?,
            }
        }
        Some("default-set") => {
            let set = v
                .get("set")
                .and_then(Value::as_object)
                .ok_or(format!("{what}: needs a \"set\" object"))?;
            let mut cleanup_style = None;
            let mut pinned_styles = None;
            for (key, value) in set {
                match key.as_str() {
                    "cleanupStyle" => {
                        cleanup_style = Some(match value {
                            Value::Null => None,
                            Value::String(s) if s == "raw" || valid_id(s) => Some(s.clone()),
                            _ => return Err(format!("{what}: cleanupStyle must be a style id, \"raw\" or null")),
                        })
                    }
                    "pinnedStyles" => {
                        pinned_styles = Some(match value {
                            Value::Null => None,
                            other => {
                                let list = string_list(Some(other))
                                    .ok_or(format!("{what}: pinnedStyles must be a list of style ids or null"))?;
                                check_pins(&what, &list)?;
                                Some(list)
                            }
                        })
                    }
                    other => return Err(format!("{what}: “{other}” is not a default an edit can set")),
                }
            }
            if cleanup_style.is_none() && pinned_styles.is_none() {
                return Err(format!("{what}: sets nothing"));
            }
            OpBody::DefaultSet { cleanup_style, pinned_styles }
        }
        Some(other) => return Err(format!("{what}: unknown type “{other}”")),
        None => return Err(format!("{what}: missing \"type\"")),
    };
    Ok(Op {
        id,
        device,
        created_at,
        body,
    })
}

/// The `set` of a `style-set` edit, checked like the same fields in a v2
/// layer file. Unknown fields refuse the edit.
fn parse_style_patch(what: &str, set: &serde_json::Map<String, Value>) -> Result<StylePatch, String> {
    fn text(what: &str, key: &str, v: &Value) -> Result<Option<String>, String> {
        match v {
            Value::Null => Ok(None),
            Value::String(s) => Ok(Some(s.clone())),
            _ => Err(format!("{what}: {key} must be text or null")),
        }
    }
    let mut p = StylePatch::default();
    for (key, value) in set {
        match key.as_str() {
            "label" => {
                let label = value.as_str().ok_or(format!("{what}: label must be text"))?;
                if label.trim().is_empty() || label.chars().count() > 40 || label.chars().any(breaks_line) {
                    return Err(format!("{what}: label must be 1–40 characters"));
                }
                p.label = Some(label.to_string());
            }
            "prompt" => {
                let prompt = value.as_str().ok_or(format!("{what}: prompt must be text"))?;
                if prompt.trim().is_empty() || prompt.chars().count() > MAX_PROMPT {
                    return Err(format!("{what}: prompt must be 1–{MAX_PROMPT} characters"));
                }
                p.prompt = Some(prompt.to_string());
            }
            "model" => p.model = Some(text(what, key, value)?),
            "expressionOverride" => p.expression_override = Some(text(what, key, value)?),
            "icon" => p.icon = Some(text(what, key, value)?),
            "kind" => p.kind = Some(text(what, key, value)?),
            "guidance" => p.guidance = Some(text(what, key, value)?),
            "note" => {
                let note = text(what, key, value)?;
                check_note(what, &note)?;
                p.note = Some(note);
            }
            "models" => {
                p.models = Some(match value {
                    Value::Null => None,
                    Value::Object(map) => {
                        let mut models = BTreeMap::new();
                        for (provider, m) in map {
                            let m = m.as_str().ok_or(format!("{what}: models.{provider} must be text"))?;
                            models.insert(provider.clone(), m.to_string());
                        }
                        Some(models)
                    }
                    _ => return Err(format!("{what}: models must be an object or null")),
                })
            }
            "expression" => {
                p.expression = Some(match value {
                    Value::Null => None,
                    n => match n.as_u64() {
                        Some(e) if e <= 4 => Some(e as u8),
                        _ => return Err(format!("{what}: expression must be 0 to 4 or null")),
                    },
                })
            }
            "hidden" => {
                p.hidden = Some(match value {
                    Value::Null => None,
                    Value::Bool(b) => Some(*b),
                    _ => return Err(format!("{what}: hidden must be true, false or null")),
                })
            }
            other => return Err(format!("{what}: “{other}” is not a style field")),
        }
    }
    if set.is_empty() {
        return Err(format!("{what}: sets nothing"));
    }
    let flat = |f: &Option<Option<String>>| f.clone().flatten();
    check_style_extras(
        what,
        flat(&p.model).as_deref(),
        p.models.clone().flatten().as_ref(),
        flat(&p.expression_override).as_deref(),
        flat(&p.icon).as_deref(),
        flat(&p.kind).as_deref(),
        flat(&p.guidance).as_deref(),
    )?;
    Ok(p)
}

/// When an edit was made, as (UTC seconds, nanoseconds), for ordering edits
/// by time whatever the text form (`…:00Z`, `…:00.500Z`, `…+02:00`). A time
/// that doesn't parse sorts first.
pub fn time_key(s: &str) -> (i64, u32) {
    fn num(b: &[u8]) -> Option<i64> {
        b.iter().try_fold(0i64, |n, d| d.is_ascii_digit().then(|| n * 10 + i64::from(d - b'0')))
    }
    let b = s.as_bytes();
    if !looks_like_iso_time(s) {
        return (i64::MIN, 0);
    }
    let (Some(y), Some(mo), Some(d), Some(h), Some(mi), Some(se)) = (
        num(&b[0..4]),
        num(&b[5..7]),
        num(&b[8..10]),
        num(&b[11..13]),
        num(&b[14..16]),
        num(&b[17..19]),
    ) else {
        return (i64::MIN, 0);
    };
    let mut i = 19;
    let mut nanos: u32 = 0;
    if b.get(i) == Some(&b'.') {
        i += 1;
        let mut digits = 0;
        while let Some(c) = b.get(i).filter(|c| c.is_ascii_digit()) {
            if digits < 9 {
                nanos = nanos * 10 + u32::from(c - b'0');
                digits += 1;
            }
            i += 1;
        }
        nanos *= 10u32.pow(9 - digits);
    }
    let offset = match b.get(i) {
        Some(b'+') | Some(b'-') if b.len() >= i + 6 => {
            let sign = if b[i] == b'+' { 1 } else { -1 };
            match (num(&b[i + 1..i + 3]), num(&b[i + 4..i + 6])) {
                (Some(oh), Some(om)) => sign * (oh * 3600 + om * 60),
                _ => 0,
            }
        }
        _ => 0,
    };
    // Days from civil (Howard Hinnant).
    let (y, m) = if mo <= 2 { (y - 1, mo + 9) } else { (y, mo - 3) };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * m + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    (days * 86_400 + h * 3600 + mi * 60 + se - offset, nanos)
}

/// Readers before writers (`pack/README.md`, Versions): whether every
/// device whose status file is given reads profile `profile_version` and
/// every edit type in `op_types`. A status without `reads` (an app from
/// before 0.9.13, or a phone) counts as reading version 1 and only
/// `correction`, `term` and `tombstone`. No status files at all proves
/// nothing, so the answer is no. VoiceFlow 0.9.21: `app-style` also needs
/// `reads.features` to contain `APP_STYLE_REFS_FEATURE` (an app rule may
/// name a built-in or PC style), so 0.9.13 to 0.9.20 don't count as ready.
pub fn readers_ready(statuses: &[Value], profile_version: u64, op_types: &[&str]) -> bool {
    const BEFORE_READS: &[&str] = &["correction", "term", "tombstone"];
    !statuses.is_empty()
        && statuses.iter().all(|status| match status.get("reads") {
            Some(reads) => {
                let profiles: Vec<u64> = reads
                    .get("profile")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(Value::as_u64).collect())
                    .unwrap_or_default();
                let ops: Vec<&str> = reads
                    .get("op")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(Value::as_str).collect())
                    .unwrap_or_default();
                let features: Vec<&str> = reads
                    .get("features")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(Value::as_str).collect())
                    .unwrap_or_default();
                profiles.contains(&profile_version)
                    && op_types.iter().all(|t| ops.contains(t))
                    && (!op_types.contains(&"app-style") || features.contains(&APP_STYLE_REFS_FEATURE))
            }
            None => profile_version == 1 && op_types.iter().all(|t| BEFORE_READS.contains(t)),
        })
}

/// `YYYY-MM-DDTHH:MM:SS…`: enough to sort by and to reject nonsense.
pub fn looks_like_iso_time(s: &str) -> bool {
    let b = s.as_bytes();
    b.len() >= 19
        && b[..4].iter().all(u8::is_ascii_digit)
        && b[4] == b'-'
        && b[5..7].iter().all(u8::is_ascii_digit)
        && b[7] == b'-'
        && b[8..10].iter().all(u8::is_ascii_digit)
        && b[10] == b'T'
        && b[11..13].iter().all(u8::is_ascii_digit)
        && b[13] == b':'
        && b[14..16].iter().all(u8::is_ascii_digit)
        && b[16] == b':'
}

#[cfg(test)]
mod tests {
    use super::*;

    fn personal(body: &str) -> String {
        format!(
            r#"{{"schema":"voiceflow-profile","schemaVersion":1,"layer":"personal","user":"sam",{body}}}"#
        )
    }

    #[test]
    fn refuses_oversized_lists_and_notes() {
        let many: Vec<String> = (0..51).map(|i| format!("\"f{i}\"")).collect();
        let p = personal(&format!(r#""corrections":[{{"id":"c1","from":[{}],"to":"X"}}]"#, many.join(",")));
        assert!(parse_profile(&p, Layer::Personal, "sam").unwrap_err().contains("more than"));
        let p = personal(&format!(r#""terms":[{{"id":"t1","text":"A","note":"{}"}}]"#, "n".repeat(501)));
        assert!(parse_profile(&p, Layer::Personal, "sam").unwrap_err().contains("note"));
        let p = personal(r#""privacy":{"titleDenyRe":["(?i)private","^(a+)+$"]}"#);
        assert!(parse_profile(&p, Layer::Personal, "sam").is_ok(), "Rust syntax is fine; matching is linear");
        let re = title_pattern("^(a+)+$").unwrap();
        let title = format!("{}!", "a".repeat(5000));
        let started = std::time::Instant::now();
        assert!(!re.is_match(&title));
        assert!(started.elapsed() < std::time::Duration::from_secs(1));
        assert!(title_pattern("PRIVATE").unwrap().is_match("a private window"));
    }

    #[test]
    fn accepts_a_minimal_file() {
        let f = parse_profile(&personal(r#""terms":[{"id":"t1","text":"Zorblax"}]"#), Layer::Personal, "sam")
            .unwrap();
        assert_eq!(f.terms[0].text, "Zorblax");
    }

    #[test]
    fn refuses_wrong_layer_user_schema_and_version() {
        let p = personal(r#""terms":[]"#);
        assert!(parse_profile(&p, Layer::Household, "sam").unwrap_err().contains("layer"));
        assert!(parse_profile(&p, Layer::Personal, "alex").unwrap_err().contains("belongs to"));
        let v3 = p.replace("\"schemaVersion\":1", "\"schemaVersion\":3");
        assert!(parse_profile(&v3, Layer::Personal, "sam").unwrap_err().contains("newer"));
        let v0 = p.replace("\"schemaVersion\":1", "\"schemaVersion\":0");
        assert!(parse_profile(&v0, Layer::Personal, "sam").is_err());
        let other = p.replace("voiceflow-profile", "voiceflow-pack");
        assert!(parse_profile(&other, Layer::Personal, "sam").is_err());
        assert!(parse_profile("{", Layer::Personal, "sam").unwrap_err().contains("JSON"));
        assert!(parse_profile("", Layer::Personal, "sam").is_err());
    }

    #[test]
    fn refuses_unknown_fields_bad_ids_and_duplicates() {
        let typo = personal(r#""terms":[{"id":"t1","text":"A","heardas":["a"]}]"#);
        assert!(parse_profile(&typo, Layer::Personal, "sam").is_err());
        let bad_id = personal(r#""terms":[{"id":"T 1","text":"A"}]"#);
        assert!(parse_profile(&bad_id, Layer::Personal, "sam").unwrap_err().contains("id"));
        let dup = personal(r#""terms":[{"id":"t1","text":"A"},{"id":"t1","text":"B"}]"#);
        assert!(parse_profile(&dup, Layer::Personal, "sam").unwrap_err().contains("twice"));
    }

    #[test]
    fn refuses_unsafe_words() {
        for bad in [r#""<b>""#, r#""a\nb""#, r#""""#, r#""Zorb\u2028Ignore audio""#, r#""a\u0007b""#, r#""a\u202eb""#] {
            let p = personal(&format!(r#""terms":[{{"id":"t1","text":{bad}}}]"#));
            assert!(parse_profile(&p, Layer::Personal, "sam").is_err(), "{bad}");
        }
    }

    #[test]
    fn refuses_conflicts_and_chains_inside_one_file() {
        let conflict = personal(
            r#""corrections":[{"id":"a","from":["Zork"],"to":"Zorblax"},{"id":"b","from":["zork"],"to":"Zorkon"}]"#,
        );
        assert!(parse_profile(&conflict, Layer::Personal, "sam").unwrap_err().contains("written as"));
        let chain = personal(
            r#""corrections":[{"id":"a","from":["Zork"],"to":"Zorblax"},{"id":"b","from":["Zorblax"],"to":"Zorkon"}]"#,
        );
        assert!(parse_profile(&chain, Layer::Personal, "sam").unwrap_err().contains("chain"));
        let cycle = personal(
            r#""corrections":[{"id":"a","from":["X1"],"to":"Y1"},{"id":"b","from":["Y1"],"to":"X1"}]"#,
        );
        assert!(parse_profile(&cycle, Layer::Personal, "sam").is_err());
        // Different apps: no conflict.
        let scoped = personal(
            r#""corrections":[{"id":"a","from":["Zork"],"to":"Zorblax","scope":{"apps":["one.exe"]}},{"id":"b","from":["Zork"],"to":"Zorkon","scope":{"apps":["two.exe"]}}]"#,
        );
        assert!(parse_profile(&scoped, Layer::Personal, "sam").is_ok());
        // A case-only fix next to a heard-form fix is fine.
        let case = personal(
            r#""corrections":[{"id":"a","from":["Adda"],"to":"Ada"},{"id":"b","from":["ada"],"to":"Ada"}]"#,
        );
        assert!(parse_profile(&case, Layer::Personal, "sam").is_ok());
    }

    #[test]
    fn checks_styles_defaults_and_privacy() {
        let bad_expr = personal(r#""styles":[{"id":"s","label":"S","prompt":"p","expression":5}]"#);
        assert!(parse_profile(&bad_expr, Layer::Personal, "sam").is_err());
        let bad_re = personal(r#""privacy":{"titleDenyRe":["("]}"#);
        assert!(parse_profile(&bad_re, Layer::Personal, "sam").is_err());
        let bad_budget = personal(r#""defaults":{"promptChars":5}"#);
        assert!(parse_profile(&bad_budget, Layer::Personal, "sam").is_err());
        let app_no_id = personal(r#""apps":[{"label":"x"}]"#);
        assert!(parse_profile(&app_no_id, Layer::Personal, "sam").is_err());
    }

    #[test]
    fn parses_c1_ops_and_term_ops() {
        let c1 = r#"{
            "schema": "voiceflow-op", "version": 1,
            "id": "pc1a2b3c4d-24ab6fff-c3ae-4c3a-9d55-0045f03d22cf",
            "device": "pc1a2b3c4d", "createdAt": "2026-11-17T17:40:44.453Z",
            "type": "correction", "from": ["Zork"], "to": "Zorblax",
            "scope": { "apps": ["*"] }, "note": "seed"
        }"#;
        let op = parse_op(c1, Some("pc1a2b3c4d-24ab6fff-c3ae-4c3a-9d55-0045f03d22cf")).unwrap();
        assert!(matches!(op.body, OpBody::Correction { .. }));
        assert!(parse_op(c1, Some("other")).unwrap_err().contains("file name"));
        let term = r#"{"schema":"voiceflow-op","version":1,"id":"dev1-x","device":"dev1",
            "createdAt":"2026-11-17T00:00:00Z","type":"term","term":{"text":"Quibbit","heardAs":["Kwibbit"]}}"#;
        let op = parse_op(term, None).unwrap();
        assert!(matches!(op.body, OpBody::Term(ref t) if t.text == "Quibbit"));
        let wrong_device = term.replace("\"id\":\"dev1-x\"", "\"id\":\"dev2-x\"");
        assert!(parse_op(&wrong_device, None).is_err());
        let unknown = term.replace("\"type\":\"term\"", "\"type\":\"rename\"");
        assert!(parse_op(&unknown, None).unwrap_err().contains("unknown type"));
        let bad_time = term.replace("2026-11-17T00:00:00Z", "yesterday");
        assert!(parse_op(&bad_time, None).is_err());
    }

    fn personal_v2(body: &str) -> String {
        format!(r#"{{"schema":"voiceflow-profile","schemaVersion":2,"layer":"personal","user":"sam",{body}}}"#)
    }

    #[test]
    fn version_2_style_fields_need_version_2() {
        let body = r#""styles":[{"id":"clean","label":"Clean","prompt":"p","icon":"🧹","kind":"casual","expression":2,
            "guidance":"g","expressionOverride":"o","models":{"openai":"gpt-5.4-mini","anthropic":"claude-haiku"},"hidden":false}],
            "defaults":{"pinnedStyles":["clean"]},"apps":[{"id":"kite.exe","style":"clean","expression":1}]"#;
        let f = parse_profile(&personal_v2(body), Layer::Personal, "sam").unwrap();
        assert_eq!(f.styles[0].icon.as_deref(), Some("🧹"));
        assert_eq!(f.styles[0].models.as_ref().unwrap()["openai"], "gpt-5.4-mini");
        assert_eq!(f.defaults.pinned_styles.as_deref(), Some(&["clean".to_string()][..]));
        // The same in a version 1 file: refused, with the fix.
        let err = parse_profile(&personal(body), Layer::Personal, "sam").unwrap_err();
        assert!(err.contains("schemaVersion 2"), "{err}");
        let err = parse_profile(&personal(r#""defaults":{"pinnedStyles":["a"]}"#), Layer::Personal, "sam").unwrap_err();
        assert!(err.contains("pinnedStyles"), "{err}");
        // Even set to null: an older app would refuse the unknown key.
        let err = parse_profile(&personal(r#""styles":[{"id":"s","label":"S","prompt":"p","hidden":null}]"#), Layer::Personal, "sam")
            .unwrap_err();
        assert!(err.contains("hidden"), "{err}");
        assert!(parse_profile(&personal(r#""defaults":{"pinnedStyles":null}"#), Layer::Personal, "sam").is_err());
        // Version 1 fields are fine in either version, and apps[].expression
        // is version 1 (read since 0.9.7).
        let v1 = personal(r#""styles":[{"id":"s","label":"S","prompt":"p","model":"m","expression":3}],"apps":[{"id":"kite.exe","expression":4}]"#);
        assert!(parse_profile(&v1, Layer::Personal, "sam").is_ok());
        assert!(parse_profile(&v1.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), Layer::Personal, "sam").is_ok());
    }

    #[test]
    fn version_2_fields_are_checked() {
        for bad in [
            r#""styles":[{"id":"s","label":"S","prompt":"p","icon":"seventeen chars!!"}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","icon":"a\nb"}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","kind":"pirate"}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","kind":"fun_"}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","models":{"azure":"m"}}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","models":{"openai":""}}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","hidden":"yes"}]"#,
            r#""styles":[{"id":"s","label":"S","prompt":"p","iconn":"x"}]"#,
            r#""defaults":{"pinnedStyles":["Not An Id"]}"#,
            r#""defaults":{"pinnedStyles":["a","b","c","d","e","f","g","h","i","j","k","l","m"]}"#,
            r#""apps":[{"id":"kite.exe","expression":5}]"#,
        ] {
            assert!(parse_profile(&personal_v2(bad), Layer::Personal, "sam").is_err(), "{bad}");
        }
        assert!(valid_kind("fun_pirate") && valid_kind("custom") && !valid_kind("fun_Pirate"));
        let g = "g".repeat(2001);
        assert!(parse_profile(&personal_v2(&format!(r#""styles":[{{"id":"s","label":"S","prompt":"p","guidance":"{g}"}}]"#)), Layer::Personal, "sam").is_err());
        // A ZWJ emoji is one icon.
        assert!(parse_profile(&personal_v2(r#""styles":[{"id":"s","label":"S","prompt":"p","icon":"🧑‍💻"}]"#), Layer::Personal, "sam").is_ok());
    }

    #[test]
    fn style_and_default_edits_parse_and_are_checked() {
        let base = r#"{"schema":"voiceflow-op","version":1,"id":"ph1-a","device":"ph1","createdAt":"2026-11-19T00:00:00Z""#;
        let op = parse_op(&format!(r#"{base},"type":"style-set","style":"clean","set":{{"expression":3,"model":null}}}}"#), None).unwrap();
        match op.body {
            OpBody::StyleSet { ref style, ref set } => {
                assert_eq!(style, "clean");
                assert_eq!(set.expression, Some(Some(3)));
                assert_eq!(set.model, Some(None));
                assert_eq!(set.prompt, None);
            }
            ref other => panic!("{other:?}"),
        }
        assert_eq!(op.body.type_name(), "style-set");
        for bad in [
            r#""type":"style-set","style":"clean","set":{}"#,
            r#""type":"style-set","style":"clean","set":{"label":null}"#,
            r#""type":"style-set","style":"clean","set":{"prompt":" "}"#,
            r#""type":"style-set","style":"clean","set":{"colour":"red"}"#,
            r#""type":"style-set","style":"raw","set":{"expression":1}"#,
            r#""type":"style-set","style":"clean","set":{"expression":7}"#,
            r#""type":"style-set","style":"clean","set":{"models":{"azure":"m"}}"#,
            r#""type":"style-set","style":"clean""#,
            r#""type":"default-set","set":{}"#,
            r#""type":"default-set","set":{"language":"en"}"#,
            r#""type":"default-set","set":{"cleanupStyle":"Bad Id"}"#,
            r#""type":"default-set","set":{"pinnedStyles":"clean"}"#,
        ] {
            assert!(parse_op(&format!("{base},{bad}}}"), None).is_err(), "{bad}");
        }
        let d = parse_op(&format!(r#"{base},"type":"default-set","set":{{"cleanupStyle":"raw","pinnedStyles":null}}}}"#), None).unwrap();
        assert_eq!(
            d.body,
            OpBody::DefaultSet { cleanup_style: Some(Some("raw".into())), pinned_styles: Some(None) }
        );
        // Not writable here yet: readers come first.
        assert!(UPLOADABLE_OP_TYPES == ["correction", "term", "tombstone"], "app-style, style-set and default-set stay on this PC");
        assert!(OP_TYPES_READ.contains(&"style-set") && OP_TYPES_READ.contains(&"default-set"));
    }

    #[test]
    fn readers_ready_needs_every_device() {
        let new = serde_json::json!({ "reads": { "profile": [1, 2], "op": OP_TYPES_READ } });
        let old = serde_json::json!({ "version": "0.9.12" });
        assert!(readers_ready(&[new.clone(), new.clone()], 2, &["style-set"]));
        assert!(!readers_ready(&[new.clone(), old.clone()], 2, &[]), "an older app reads version 1 only");
        assert!(!readers_ready(&[new.clone(), old.clone()], 1, &["default-set"]));
        assert!(readers_ready(&[new.clone(), old.clone()], 1, &["correction", "tombstone"]));
        assert!(!readers_ready(&[new, old.clone()], 1, &["app-style"]), "a phone or an older app: no app-style");
        assert!(!readers_ready(&[], 1, &[]), "no status files prove nothing");
    }

    #[test]
    fn app_style_readers_must_also_read_device_style_refs() {
        // 0.9.13 to 0.9.20 report app-style but not the 0.9.21 references.
        let before = serde_json::json!({ "reads": { "profile": [1, 2], "op": OP_TYPES_READ } });
        let now = serde_json::json!({ "reads": { "profile": [1, 2], "op": OP_TYPES_READ, "features": READ_FEATURES } });
        assert!(!readers_ready(&[now.clone(), before.clone()], 1, &["app-style"]));
        assert!(readers_ready(&[now.clone(), now.clone()], 1, &["app-style"]));
        assert!(readers_ready(&[now, before], 1, &["style-set"]), "other types don't need it");
        // Reporting it doesn't make anything uploadable.
        assert!(!UPLOADABLE_OP_TYPES.contains(&"app-style"));
    }

    #[test]
    fn app_style_edits_parse_and_are_checked() {
        let base = r#"{"schema":"voiceflow-op","version":1,"id":"dev1-a","device":"dev1","createdAt":"2026-11-18T00:00:00Z","type":"app-style""#;
        let ok = parse_op(&format!(r#"{base},"app":"Kite.exe","expression":1}}"#), None).unwrap();
        assert_eq!(
            ok.body,
            OpBody::AppStyle { app: "kite.exe".into(), style: None, expression: Some(1) }
        );
        let raw = parse_op(&format!(r#"{base},"app":"kite.exe","style":"raw"}}"#), None).unwrap();
        assert!(matches!(raw.body, OpBody::AppStyle { style: Some(ref s), .. } if s == "raw"));
        assert!(parse_op(&format!(r#"{base},"app":"kite.exe"}}"#), None).is_err(), "nothing to set");
        assert!(parse_op(&format!(r#"{base},"app":"kite.exe","expression":5}}"#), None).is_err());
        assert!(parse_op(&format!(r#"{base},"app":"kite.exe","expression":-1}}"#), None).is_err());
        assert!(parse_op(&format!(r#"{base},"app":"kite.exe","style":"Bad Id"}}"#), None).is_err());
        assert!(parse_op(&format!(r#"{base},"app":"a b","expression":1}}"#), None).is_err());
    }
}
