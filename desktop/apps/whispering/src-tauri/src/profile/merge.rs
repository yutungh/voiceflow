//! VoiceFlow word lists: merging layers into one snapshot.
//!
//! Precedence (PLAN-v2): defaults < household < generated < personal < app
//! edits. Words, corrections and styles keep stable ids; a later layer's
//! entry with the same id replaces the earlier one. After merging, the
//! result is checked again: corrections that collide (one heard form, two
//! spellings), chain (one's result is another's heard form) or loop are
//! resolved in favour of the higher layer and reported, never applied both
//! ways; references (the default style, app rules' words) must exist.

use std::collections::{BTreeMap, HashMap, HashSet};

use serde::Serialize;
use serde_json::Value;

use super::model::{
    norm, time_key, Correction, Defaults, Layer, Op, OpBody, Privacy, ProfileFile, Scope, Style, StylePatch, Term,
    EVERY_APP,
};

/// Prompt budget when no layer sets one (SPEC §3.3).
pub const DEFAULT_PROMPT_CHARS: u32 = 600;
pub const DEFAULT_PROMPT_TERMS: u32 = 40;
pub const DEFAULT_PRIORITY: i64 = 50;

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MergedTerm {
    pub id: String,
    pub text: String,
    pub heard_as: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gloss: Option<String>,
    pub priority: i64,
    pub apps: Vec<String>,
    pub layer: Layer,
    /// Edits only: the device that made it and when.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_at: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MergedCorrection {
    pub id: String,
    pub from: Vec<String>,
    pub to: String,
    pub scope: Scope,
    pub layer: Layer,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_at: Option<String>,
}

/// A merged style. The version 2 fields are left out of the JSON when unset,
/// so a snapshot without them keeps the id it had before 0.9.13.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MergedStyle {
    pub id: String,
    pub label: String,
    pub prompt: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expression: Option<u8>,
    pub layer: Layer,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub models: BTreeMap<String, String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expression_override: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub guidance: Option<String>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub hidden: bool,
    /// The `style-set` edits in force on it, oldest first (for undo).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub edit_ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MergedDefaults {
    #[serde(flatten)]
    pub values: Defaults,
    pub prompt_chars_effective: u32,
    pub prompt_terms_effective: u32,
    /// Which layer set each default (only those set by a profile layer).
    pub from_layer: BTreeMap<String, Layer>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Issue {
    /// `correction-conflict`, `correction-chain`, `word-corrected`,
    /// `missing-style`, `missing-word`, `orphan-tombstone`, `duplicate-word`,
    /// or a layer/edit read problem (`layer-rejected`, `edit-rejected`, ...).
    pub code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub layer: Option<Layer>,
    pub ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    /// Changes whenever the merged content changes; pinned per dictation.
    pub id: String,
    pub user: String,
    pub terms: Vec<MergedTerm>,
    /// Lowest precedence first: when two apply to one form, the later wins.
    pub corrections: Vec<MergedCorrection>,
    pub styles: Vec<MergedStyle>,
    pub defaults: MergedDefaults,
    pub privacy: Privacy,
    pub apps: Vec<Value>,
    pub issues: Vec<Issue>,
}

/// Rank: layer first, then order inside the layer (edits: oldest first).
type Rank = (Layer, usize);

fn issue(code: &str, message: String, layer: Option<Layer>, ids: Vec<String>) -> Issue {
    Issue {
        code: code.to_string(),
        message,
        layer,
        ids,
    }
}

fn layer_label(layer: Layer) -> &'static str {
    match layer {
        Layer::Default => "built-in",
        Layer::Household => "household",
        Layer::Generated => "from notes",
        Layer::Personal => "personal",
        Layer::Edit => "app edit",
    }
}

/// The edits still in force: tombstones remove their target, newest first,
/// so undoing an undo brings the edit back. Oldest first in the result.
pub fn live_ops(ops: &[Op], issues: &mut Vec<Issue>) -> Vec<Op> {
    let mut sorted: Vec<&Op> = ops.iter().collect();
    // By real time, not by text: "…:00.500Z" is after "…:00Z".
    sorted.sort_by(|a, b| time_key(&a.created_at).cmp(&time_key(&b.created_at)).then(a.id.cmp(&b.id)));
    let mut seen = HashSet::new();
    sorted.retain(|op| seen.insert(op.id.clone()));
    let ids: HashSet<&str> = sorted.iter().map(|o| o.id.as_str()).collect();
    let mut dead: HashSet<String> = HashSet::new();
    for op in sorted.iter().rev() {
        if dead.contains(&op.id) {
            continue;
        }
        if let OpBody::Tombstone { target } = &op.body {
            if !ids.contains(target.as_str()) {
                issues.push(issue(
                    "orphan-tombstone",
                    format!("An undo refers to an edit that isn't here ({target})."),
                    Some(Layer::Edit),
                    vec![op.id.clone(), target.clone()],
                ));
            }
            dead.insert(target.clone());
        }
    }
    sorted
        .into_iter()
        .filter(|op| !dead.contains(&op.id) && !matches!(op.body, OpBody::Tombstone { .. }))
        .cloned()
        .collect()
}

/// Merges the layers (lowest first; `None` = layer absent) and the edits.
pub fn merge(user: &str, layers: &[(Layer, Option<&ProfileFile>)], ops: &[Op]) -> Snapshot {
    let mut issues = Vec::new();
    let live = live_ops(ops, &mut issues);

    // ---- words ---------------------------------------------------------
    let mut terms: HashMap<String, (Rank, MergedTerm)> = HashMap::new();
    let put_term = |terms: &mut HashMap<String, (Rank, MergedTerm)>, rank: Rank, t: MergedTerm| {
        match terms.get(&t.id) {
            Some((r, _)) if *r > rank => {}
            _ => {
                terms.insert(t.id.clone(), (rank, t));
            }
        }
    };
    for (layer, file) in layers {
        let Some(file) = file else { continue };
        for (i, t) in file.terms.iter().enumerate() {
            put_term(&mut terms, (*layer, i), term_from(t, *layer));
        }
    }
    for (i, op) in live.iter().enumerate() {
        if let OpBody::Term(body) = &op.body {
            put_term(
                &mut terms,
                (Layer::Edit, i),
                MergedTerm {
                    id: op.id.clone(),
                    text: body.text.clone(),
                    heard_as: body.heard_as.clone(),
                    kind: body.kind.clone(),
                    gloss: body.gloss.clone(),
                    priority: body.priority.unwrap_or(DEFAULT_PRIORITY),
                    apps: lower_apps(body.apps.clone()),
                    layer: Layer::Edit,
                    device: Some(op.device.clone()),
                    created_at: Some(op.created_at.clone()),
                },
            );
        }
    }
    // Same written word under two ids: the higher layer's entry stays.
    let mut by_text: HashMap<String, Vec<(Rank, String)>> = HashMap::new();
    for (id, (rank, t)) in &terms {
        by_text.entry(norm(&t.text)).or_default().push((*rank, id.clone()));
    }
    for (_, mut entries) in by_text {
        if entries.len() < 2 {
            continue;
        }
        entries.sort();
        let (_, keep) = entries.pop().expect("two or more");
        for (_, drop) in entries {
            let dropped = terms.remove(&drop).map(|(_, t)| t);
            let kept = &terms[&keep].1;
            if let Some(dropped) = dropped {
                // An override across layers is normal; only report a
                // duplicate inside one layer.
                if dropped.layer == kept.layer {
                    issues.push(issue(
                        "duplicate-word",
                        format!("“{}” is listed twice; using one.", kept.text),
                        Some(kept.layer),
                        vec![keep.clone(), dropped.id],
                    ));
                }
            }
        }
    }

    // ---- corrections ---------------------------------------------------
    let mut candidates: Vec<(Rank, MergedCorrection)> = Vec::new();
    for (layer, file) in layers {
        let Some(file) = file else { continue };
        for (i, c) in file.corrections.iter().enumerate() {
            candidates.push(((*layer, i), correction_from(c, *layer)));
        }
    }
    for (i, op) in live.iter().enumerate() {
        if let OpBody::Correction { from, to, scope, note } = &op.body {
            candidates.push((
                (Layer::Edit, i),
                MergedCorrection {
                    id: op.id.clone(),
                    from: from.clone(),
                    to: to.clone(),
                    scope: scope.clone(),
                    layer: Layer::Edit,
                    note: note.clone(),
                    device: Some(op.device.clone()),
                    created_at: Some(op.created_at.clone()),
                },
            ));
        }
    }
    // Same id: the higher layer replaces the lower one entirely.
    let mut by_id: HashMap<String, (Rank, MergedCorrection)> = HashMap::new();
    for (rank, c) in candidates {
        match by_id.get(&c.id) {
            Some((r, _)) if *r > rank => {}
            _ => {
                by_id.insert(c.id.clone(), (rank, c));
            }
        }
    }
    let mut ranked: Vec<(Rank, MergedCorrection)> = by_id.into_values().collect();
    // Highest first: each claims its heard forms; lower ones lose a form a
    // higher one already writes differently in every app they cover.
    ranked.sort_by_key(|a| std::cmp::Reverse(a.0));
    let mut accepted: Vec<(Rank, MergedCorrection)> = Vec::new();
    for (rank, mut c) in ranked {
        let mut keep_forms = Vec::new();
        for form in &c.from {
            let mut lost = false;
            for (_, a) in &accepted {
                if !a.scope.overlaps(&c.scope) || !a.from.iter().any(|g| norm(g) == norm(form)) {
                    continue;
                }
                if a.to != c.to {
                    issues.push(issue(
                        "correction-conflict",
                        format!(
                            "“{}” is written as “{}” ({}) and as “{}” ({}). Using “{}”.",
                            form.trim(),
                            a.to,
                            layer_label(a.layer),
                            c.to,
                            layer_label(c.layer),
                            a.to
                        ),
                        Some(c.layer),
                        vec![a.id.clone(), c.id.clone()],
                    ));
                }
                if a.scope.covers(&c.scope) {
                    lost = true;
                }
            }
            if !lost {
                keep_forms.push(form.clone());
            }
        }
        if keep_forms.is_empty() {
            continue;
        }
        c.from = keep_forms;
        accepted.push((rank, c));
    }
    // Chains and loops: a result that another correction would change
    // again. The lower one is set aside until someone fixes it.
    loop {
        let mut drop_index = None;
        'outer: for (i, (ri, a)) in accepted.iter().enumerate() {
            for (j, (rj, b)) in accepted.iter().enumerate() {
                if i == j || !a.scope.overlaps(&b.scope) || b.to == a.to {
                    continue;
                }
                if b.from.iter().any(|g| norm(g) == norm(&a.to)) {
                    let (lower, higher) = if ri < rj { (i, j) } else { (j, i) };
                    let (l, h) = (&accepted[lower].1, &accepted[higher].1);
                    issues.push(issue(
                        "correction-chain",
                        format!(
                            "“{}” → “{}” ({}) and “{}” → “{}” ({}) would chain. Using only the {} one.",
                            l.from.join("/"),
                            l.to,
                            layer_label(l.layer),
                            h.from.join("/"),
                            h.to,
                            layer_label(h.layer),
                            layer_label(h.layer),
                        ),
                        Some(l.layer),
                        vec![h.id.clone(), l.id.clone()],
                    ));
                    drop_index = Some(lower);
                    break 'outer;
                }
            }
        }
        match drop_index {
            Some(i) => {
                accepted.remove(i);
            }
            None => break,
        }
    }
    accepted.sort_by_key(|a| a.0);
    let corrections: Vec<MergedCorrection> = accepted.into_iter().map(|(_, c)| c).collect();

    // A word that a correction would rewrite (e.g. a real "Phoebus" next to
    // Phoebus → Pip): keep both, tell the person.
    let mut term_list: Vec<MergedTerm> = terms.into_values().map(|(_, t)| t).collect();
    for t in &term_list {
        let tscope = Scope { apps: t.apps.clone() };
        for c in &corrections {
            if c.scope.overlaps(&tscope) && c.from.iter().any(|f| norm(f) == norm(&t.text)) && c.to != t.text {
                issues.push(issue(
                    "word-corrected",
                    format!(
                        "The word “{}” ({}) is changed to “{}” by a spelling fix ({}).",
                        t.text,
                        layer_label(t.layer),
                        c.to,
                        layer_label(c.layer)
                    ),
                    Some(c.layer),
                    vec![t.id.clone(), c.id.clone()],
                ));
            }
        }
    }
    term_list.sort_by(|a, b| b.priority.cmp(&a.priority).then(a.text.to_lowercase().cmp(&b.text.to_lowercase())));

    // ---- styles, defaults, privacy, app rules --------------------------
    let mut styles: BTreeMap<String, MergedStyle> = BTreeMap::new();
    let mut defaults = Defaults::default();
    let mut from_layer: BTreeMap<String, Layer> = BTreeMap::new();
    let mut privacy = Privacy::default();
    let mut apps: BTreeMap<String, Value> = BTreeMap::new();
    for (layer, file) in layers {
        let Some(file) = file else { continue };
        for s in &file.styles {
            styles.insert(s.id.clone(), style_from(s, *layer));
        }
        let d = &file.defaults;
        macro_rules! take {
            ($field:ident, $name:literal) => {
                if d.$field.is_some() {
                    defaults.$field = d.$field.clone();
                    from_layer.insert($name.to_string(), *layer);
                }
            };
        }
        take!(transcription_model, "transcriptionModel");
        take!(language, "language");
        take!(cleanup_model, "cleanupModel");
        take!(cleanup_style, "cleanupStyle");
        take!(cleanup_timeout_s, "cleanupTimeoutS");
        take!(prompt_chars, "promptChars");
        take!(prompt_terms, "promptTerms");
        take!(pinned_styles, "pinnedStyles");
        for app in &file.privacy.block_apps {
            if !privacy.block_apps.contains(app) {
                privacy.block_apps.push(app.clone());
            }
        }
        for re in &file.privacy.title_deny_re {
            if !privacy.title_deny_re.contains(re) {
                privacy.title_deny_re.push(re.clone());
            }
        }
        for app in &file.apps {
            if let Some(id) = app.get("id").and_then(Value::as_str) {
                apps.insert(id.to_string(), app.clone());
            }
        }
    }
    // 0.9.13: style-set edits, oldest first, field by field: for each field
    // the newest edit wins, so an expression change on a phone never
    // overwrites a prompt edit on the PC. A style no layer has is made by
    // its edits, once they give it a label and a prompt.
    for op in &live {
        if let OpBody::StyleSet { style, set } = &op.body {
            let s = styles.entry(style.clone()).or_insert_with(|| MergedStyle {
                id: style.clone(),
                label: String::new(),
                prompt: String::new(),
                model: None,
                expression: None,
                layer: Layer::Edit,
                models: BTreeMap::new(),
                expression_override: None,
                icon: None,
                kind: None,
                guidance: None,
                hidden: false,
                edit_ids: Vec::new(),
            });
            apply_style_patch(s, set);
            s.edit_ids.push(op.id.clone());
        }
    }
    let incomplete: Vec<String> = styles
        .values()
        .filter(|s| s.label.trim().is_empty() || s.prompt.trim().is_empty())
        .map(|s| s.id.clone())
        .collect();
    for id in incomplete {
        styles.remove(&id);
        issues.push(issue(
            "style-incomplete",
            format!("Edits make a style ({id}) that has no name or no instructions yet, so it isn't offered."),
            Some(Layer::Edit),
            vec![id],
        ));
    }
    // 0.9.13: default-set edits, over the layers, field by field (newest
    // wins). null takes the edits' value away: the layers' value is back.
    let mut edit_cleanup: Option<Option<String>> = None;
    let mut edit_pins: Option<Option<Vec<String>>> = None;
    for op in &live {
        if let OpBody::DefaultSet { cleanup_style, pinned_styles } = &op.body {
            if let Some(v) = cleanup_style {
                edit_cleanup = Some(v.clone());
            }
            if let Some(v) = pinned_styles {
                edit_pins = Some(v.clone());
            }
        }
    }
    if let Some(Some(style)) = edit_cleanup {
        defaults.cleanup_style = Some(style);
        from_layer.insert("cleanupStyle".into(), Layer::Edit);
    }
    if let Some(Some(pins)) = edit_pins {
        defaults.pinned_styles = Some(pins);
        from_layer.insert("pinnedStyles".into(), Layer::Edit);
    }
    // Pins name styles that exist, once each, in order.
    if let Some(pins) = defaults.pinned_styles.take() {
        let mut kept: Vec<String> = Vec::new();
        for id in pins {
            if kept.contains(&id) {
                continue;
            }
            if styles.contains_key(&id) {
                kept.push(id);
            } else {
                issues.push(issue(
                    "missing-style",
                    format!("A pinned style isn't in any word list ({id}), so it's left out."),
                    from_layer.get("pinnedStyles").copied(),
                    vec![id],
                ));
            }
        }
        defaults.pinned_styles = Some(kept);
    }

    // VoiceFlow phase F: app-style edits (approved feedback fixes) set the
    // app rule's style and/or expression over the word lists', oldest first,
    // field by field. The checks below cover them like any other rule.
    //
    // 0.9.21 (Settings > Tidy-up > Style for each app): the merged rule also
    // says which edits are in force for the app (`editIds`, oldest first: all
    // of them, so removing the app's setting can retire every one, cached
    // edits from other devices included) and what the word lists' own rule
    // was under them (`listsRule`, null when they have none), so the app can
    // say what removing the setting brings back. `editId` stays the newest.
    let mut lists_rules: BTreeMap<String, Value> = BTreeMap::new();
    for op in &live {
        if let OpBody::AppStyle { app, style, expression } = &op.body {
            if !lists_rules.contains_key(app) {
                let under = apps.get(app).map_or(Value::Null, |rule| {
                    let mut kept = serde_json::Map::new();
                    for key in ["style", "expression"] {
                        if let Some(value) = rule.get(key) {
                            kept.insert(key.into(), value.clone());
                        }
                    }
                    Value::Object(kept)
                });
                lists_rules.insert(app.clone(), under);
            }
            let entry = apps
                .entry(app.clone())
                .or_insert_with(|| serde_json::json!({ "id": app }));
            if let Value::Object(map) = entry {
                if let Some(style) = style {
                    map.insert("style".into(), Value::String(style.clone()));
                }
                if let Some(expression) = expression {
                    map.insert("expression".into(), Value::from(*expression));
                    // 0.9.21: the edit the level comes from (absent: the word lists').
                    map.insert("expressionEditId".into(), Value::String(op.id.clone()));
                }
                map.insert("editId".into(), Value::String(op.id.clone()));
                let ids = map.entry("editIds").or_insert_with(|| Value::Array(Vec::new()));
                if let Value::Array(ids) = ids {
                    ids.push(Value::String(op.id.clone()));
                }
            }
        }
    }
    for (app, under) in lists_rules {
        if let Some(Value::Object(map)) = apps.get_mut(&app) {
            map.insert("listsRule".into(), under);
        }
    }
    if let Some(style) = defaults.cleanup_style.clone() {
        if style != "raw" && !styles.contains_key(&style) {
            issues.push(issue(
                "missing-style",
                format!("The default style “{style}” isn't in any word list, so it's ignored."),
                from_layer.get("cleanupStyle").copied(),
                vec![style],
            ));
            defaults.cleanup_style = None;
            from_layer.remove("cleanupStyle");
        }
    }
    let term_ids: HashSet<&str> = term_list.iter().map(|t| t.id.as_str()).collect();
    for (id, app) in &apps {
        if let Some(Value::Array(refs)) = app.get("terms") {
            for r in refs {
                if let Some(r) = r.as_str() {
                    if !term_ids.contains(r) {
                        issues.push(issue(
                            "missing-word",
                            format!("App rule “{id}” lists a word that doesn't exist ({r})."),
                            None,
                            vec![id.clone(), r.to_string()],
                        ));
                    }
                }
            }
        }
        if let Some(style) = app.get("style").and_then(Value::as_str) {
            // 0.9.21: a built-in style or one made on this PC is a style
            // the app has, not a missing one (`device_style_ref`).
            if style != "raw" && !styles.contains_key(style) && !device_style_ref(style) {
                issues.push(issue(
                    "missing-style",
                    format!("App rule “{id}” uses a style that doesn't exist ({style})."),
                    None,
                    vec![id.clone(), style.to_string()],
                ));
            }
        }
    }

    let merged_defaults = MergedDefaults {
        prompt_chars_effective: defaults.prompt_chars.unwrap_or(DEFAULT_PROMPT_CHARS),
        prompt_terms_effective: defaults.prompt_terms.unwrap_or(DEFAULT_PROMPT_TERMS),
        values: defaults,
        from_layer,
    };
    let mut snapshot = Snapshot {
        id: String::new(),
        user: user.to_string(),
        terms: term_list,
        corrections,
        styles: styles.into_values().collect(),
        defaults: merged_defaults,
        privacy,
        apps: apps.into_values().collect(),
        issues,
    };
    snapshot.id = snapshot_id(&snapshot);
    snapshot
}

fn lower_apps(apps: Option<Vec<String>>) -> Vec<String> {
    match apps {
        Some(list) if !list.is_empty() => list
            .into_iter()
            .map(|a| if a == EVERY_APP { a } else { a.to_lowercase() })
            .collect(),
        _ => vec![EVERY_APP.to_string()],
    }
}

fn term_from(t: &Term, layer: Layer) -> MergedTerm {
    MergedTerm {
        id: t.id.clone(),
        text: t.text.trim().to_string(),
        heard_as: t.heard_as.iter().map(|h| h.trim().to_string()).collect(),
        kind: t.kind.clone(),
        gloss: t.gloss.clone(),
        priority: t.priority.unwrap_or(DEFAULT_PRIORITY),
        apps: lower_apps(t.apps.clone()),
        layer,
        device: None,
        created_at: None,
    }
}

fn correction_from(c: &Correction, layer: Layer) -> MergedCorrection {
    MergedCorrection {
        id: c.id.clone(),
        from: c.from.iter().map(|f| f.trim().to_string()).collect(),
        to: c.to.trim().to_string(),
        scope: Scope {
            apps: lower_apps(c.scope.as_ref().map(|s| s.apps.clone())),
        },
        layer,
        note: c.note.clone(),
        device: None,
        created_at: None,
    }
}

fn style_from(s: &Style, layer: Layer) -> MergedStyle {
    MergedStyle {
        id: s.id.clone(),
        label: s.label.trim().to_string(),
        prompt: s.prompt.clone(),
        model: s.model.clone(),
        expression: s.expression,
        layer,
        models: s.models.clone().unwrap_or_default(),
        expression_override: s.expression_override.clone(),
        icon: s.icon.clone(),
        kind: s.kind.clone(),
        guidance: s.guidance.clone(),
        hidden: s.hidden.unwrap_or(false),
        edit_ids: Vec::new(),
    }
}

/// One `style-set` edit over a style: each field it names is set, or
/// cleared when it is null.
fn apply_style_patch(s: &mut MergedStyle, p: &StylePatch) {
    if let Some(label) = &p.label {
        s.label = label.trim().to_string();
    }
    if let Some(prompt) = &p.prompt {
        s.prompt = prompt.clone();
    }
    if let Some(v) = &p.model {
        s.model = v.clone();
    }
    if let Some(v) = &p.models {
        s.models = v.clone().unwrap_or_default();
    }
    if let Some(v) = p.expression {
        s.expression = v;
    }
    if let Some(v) = &p.expression_override {
        s.expression_override = v.clone();
    }
    if let Some(v) = &p.icon {
        s.icon = v.clone();
    }
    if let Some(v) = &p.kind {
        s.kind = v.clone();
    }
    if let Some(v) = &p.guidance {
        s.guidance = v.clone();
    }
    if let Some(v) = p.hidden {
        s.hidden = v.unwrap_or(false);
    }
}

/// VoiceFlow 0.9.21: the built-in styles an app rule may name by their fixed
/// ids (`vf-builtin-<key>`; the same list as `BUILT_IN_STYLE_REFS` in
/// `src/lib/profile/style-refs.ts`, and a test there checks this one).
pub const BUILTIN_STYLE_REFS: &[&str] = &[
    "vf-builtin-clean",
    "vf-builtin-ai-prompt",
    "vf-builtin-email",
    "vf-builtin-work-chat",
    "vf-builtin-notes",
    "vf-builtin-concise",
    "vf-builtin-friends",
    "vf-builtin-work",
    "vf-builtin-family",
    "vf-builtin-partner",
];

/// VoiceFlow 0.9.21: whether an app rule's style names a style kept on the
/// device rather than in the word lists: an exact built-in id, or a style
/// made on a PC (`vf-pc-` and the style's id, each capital letter written
/// as `.` and its lower case: `encodePcStyleRef` in `style-refs.ts`). Only a
/// reference that decodes canonically counts, so a broken one is still
/// reported as missing.
pub fn device_style_ref(style: &str) -> bool {
    if BUILTIN_STYLE_REFS.contains(&style) {
        return true;
    }
    let Some(body) = style.strip_prefix("vf-pc-") else {
        return false;
    };
    let bytes = body.as_bytes();
    let mut decoded = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        match bytes[i] {
            b'.' => {
                if !bytes.get(i + 1).is_some_and(u8::is_ascii_lowercase) {
                    return false;
                }
                i += 2;
            }
            b if b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-' => i += 1,
            _ => return false,
        }
        decoded += 1;
    }
    (1..=40).contains(&decoded)
}

/// FNV-1a 64 over the merged content (issues excluded): same content, same id.
pub fn snapshot_id(s: &Snapshot) -> String {
    let content = serde_json::json!({
        "user": s.user,
        "terms": s.terms,
        "corrections": s.corrections,
        "styles": s.styles,
        "defaults": s.defaults,
        "privacy": s.privacy,
        "apps": s.apps,
    });
    let text = content.to_string();
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:016x}")
}

#[cfg(test)]
mod tests {
    use super::super::model::{parse_op, parse_profile};
    use super::*;

    fn file(layer: Layer, user: &str, body: &str) -> ProfileFile {
        let owner = if layer == Layer::Household { "household" } else { user };
        parse_profile(
            &format!(
                r#"{{"schema":"voiceflow-profile","schemaVersion":1,"layer":"{}","user":"{owner}",{body}}}"#,
                layer.as_str()
            ),
            layer,
            user,
        )
        .unwrap()
    }

    fn op(id: &str, at: &str, body: &str) -> Op {
        parse_op(
            &format!(
                r#"{{"schema":"voiceflow-op","version":1,"id":"dev1-{id}","device":"dev1","createdAt":"{at}",{body}}}"#
            ),
            None,
        )
        .unwrap()
    }

    #[test]
    fn edits_are_ordered_by_real_time_not_text() {
        // The undo-of-the-undo is later by the clock but sorts first as text.
        let word = op("a", "2026-01-01T10:00:00Z", r#""type":"term","term":{"text":"Quibbit"}"#);
        let undo = op("b", "2026-01-02T10:00:00Z", r#""type":"tombstone","target":"dev1-a""#);
        let redo = op("c", "2026-01-02T10:00:00.500Z", r#""type":"tombstone","target":"dev1-b""#);
        let mut issues = Vec::new();
        let live = live_ops(&[word, undo, redo], &mut issues);
        assert_eq!(live.len(), 1, "the redo wins, so the word is back");
        // Offsets: 12:00+02:00 is 10:00Z, before 10:30Z.
        assert!(time_key("2026-01-02T12:00:00+02:00") < time_key("2026-01-02T10:30:00Z"));
        assert!(time_key("2026-01-02T10:00:00Z") < time_key("2026-01-02T10:00:00.5Z"));
        assert_eq!(time_key("2026-01-02T10:00:00.5Z"), time_key("2026-01-02T10:00:00.500000Z"));
        assert_eq!(time_key("1970-01-01T00:00:00Z"), (0, 0));
    }

    /// The shared test vectors in `pack/vectors/merge.json`.
    #[test]
    fn pack_vectors() {
        let text = include_str!("../../../../../../pack/vectors/merge.json");
        let doc: Value = serde_json::from_str(text).expect("merge.json parses");
        let cases = doc["cases"].as_array().expect("cases");
        assert!(cases.len() >= 10);
        run_vector_cases(cases);
    }

    /// The merge cases added with VoiceFlow Android 0.8.2 (`pack/vectors/wordlists.json`).
    #[test]
    fn pack_wordlist_vectors() {
        let text = include_str!("../../../../../../pack/vectors/wordlists.json");
        let doc: Value = serde_json::from_str(text).expect("wordlists.json parses");
        let cases = doc["merge"].as_array().expect("merge");
        assert!(!cases.is_empty());
        run_vector_cases(cases);
    }

    fn run_vector_cases(cases: &[Value]) {
        for case in cases {
            let name = case["name"].as_str().unwrap_or("?");
            let user = case["user"].as_str().unwrap_or("sam");
            let mut files: Vec<(Layer, ProfileFile)> = Vec::new();
            let mut rejected: Vec<&str> = Vec::new();
            for (layer, key) in [
                (Layer::Household, "household"),
                (Layer::Generated, "generated"),
                (Layer::Personal, "personal"),
            ] {
                let Some(file) = case.get(key) else { continue };
                match parse_profile(&file.to_string(), layer, user) {
                    Ok(parsed) => files.push((layer, parsed)),
                    Err(_) => rejected.push(key),
                }
            }
            let ops: Vec<Op> = case["ops"]
                .as_array()
                .map(|list| {
                    list.iter()
                        .map(|o| parse_op(&o.to_string(), None).unwrap_or_else(|e| panic!("{name}: {e}")))
                        .collect()
                })
                .unwrap_or_default();
            let layers: Vec<(Layer, Option<&ProfileFile>)> = files.iter().map(|(l, f)| (*l, Some(f))).collect();
            let s = merge(user, &layers, &ops);
            let expect = &case["expect"];

            let terms: BTreeMap<String, String> = s.terms.iter().map(|t| (t.id.clone(), t.text.clone())).collect();
            let want_terms: BTreeMap<String, String> = expect["terms"]
                .as_object()
                .unwrap()
                .iter()
                .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_string()))
                .collect();
            assert_eq!(terms, want_terms, "{name}: words");

            let corrections: Vec<String> =
                s.corrections.iter().map(|c| format!("{} -> {}", c.from.join("/"), c.to)).collect();
            let want_corrections: Vec<String> = expect["corrections"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap().to_string())
                .collect();
            assert_eq!(corrections, want_corrections, "{name}: corrections");

            let mut issues: Vec<&str> = s.issues.iter().map(|i| i.code.as_str()).collect();
            issues.sort();
            let want_issues: Vec<&str> = expect["issues"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
            assert_eq!(issues, want_issues, "{name}: issues");

            let want_rejected: Vec<&str> =
                expect["rejected"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
            assert_eq!(rejected, want_rejected, "{name}: rejected layers");
        }
    }

    /// The shared style vectors in `pack/vectors/styles.json` (0.9.13):
    /// full merged styles, defaults, app rules, issues, refused files and
    /// refused edits.
    #[test]
    fn pack_style_vectors() {
        let text = include_str!("../../../../../../pack/vectors/styles.json");
        let doc: Value = serde_json::from_str(text).expect("styles.json parses");
        let cases = doc["cases"].as_array().expect("cases");
        assert!(cases.len() >= 12);
        for case in cases {
            let name = case["name"].as_str().unwrap_or("?");
            let user = case["user"].as_str().unwrap_or("sam");
            let mut files: Vec<(Layer, ProfileFile)> = Vec::new();
            let mut rejected: Vec<String> = Vec::new();
            for (layer, key) in [
                (Layer::Household, "household"),
                (Layer::Generated, "generated"),
                (Layer::Personal, "personal"),
            ] {
                let Some(file) = case.get(key) else { continue };
                match parse_profile(&file.to_string(), layer, user) {
                    Ok(parsed) => files.push((layer, parsed)),
                    Err(_) => rejected.push(key.to_string()),
                }
            }
            let mut ops = Vec::new();
            let mut skipped: Vec<String> = Vec::new();
            for o in case["ops"].as_array().cloned().unwrap_or_default() {
                match parse_op(&o.to_string(), None) {
                    Ok(op) => ops.push(op),
                    Err(_) => skipped.push(o["id"].as_str().unwrap_or("?").to_string()),
                }
            }
            let layers: Vec<(Layer, Option<&ProfileFile>)> = files.iter().map(|(l, f)| (*l, Some(f))).collect();
            let s = merge(user, &layers, &ops);
            let expect = &case["expect"];

            let styles: serde_json::Map<String, Value> = s
                .styles
                .iter()
                .map(|st| {
                    let mut v = serde_json::to_value(st).unwrap();
                    let obj = v.as_object_mut().unwrap();
                    obj.remove("id");
                    obj.remove("editIds");
                    (st.id.clone(), v)
                })
                .collect();
            assert_eq!(Value::Object(styles), expect["styles"], "{name}: styles");

            let d = &s.defaults.values;
            let mut defaults = serde_json::Map::new();
            if let Some(c) = &d.cleanup_style {
                defaults.insert("cleanupStyle".into(), Value::from(c.clone()));
            }
            if let Some(p) = &d.pinned_styles {
                defaults.insert("pinnedStyles".into(), Value::from(p.clone()));
            }
            assert_eq!(Value::Object(defaults), expect["defaults"], "{name}: defaults");

            let apps: serde_json::Map<String, Value> = s
                .apps
                .iter()
                .map(|a| {
                    let mut rule = serde_json::Map::new();
                    for key in ["style", "expression"] {
                        if let Some(v) = a.get(key) {
                            rule.insert(key.into(), v.clone());
                        }
                    }
                    (a["id"].as_str().unwrap().to_string(), Value::Object(rule))
                })
                .collect();
            assert_eq!(Value::Object(apps), expect["apps"], "{name}: apps");

            let mut issues: Vec<&str> = s.issues.iter().map(|i| i.code.as_str()).collect();
            issues.sort();
            let want: Vec<&str> = expect["issues"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
            assert_eq!(issues, want, "{name}: issues");
            assert_eq!(Value::from(rejected), expect["rejected"], "{name}: rejected layers");
            assert_eq!(Value::from(skipped), expect["skippedOps"], "{name}: skipped edits");
        }
    }

    #[test]
    fn a_larger_prompt_budget_from_the_personal_file_is_used() {
        // A personal file may raise the budget (1200 characters, 80 words)
        // so pronunciation hints fit; 0.9.13 keeps reading it as before.
        let h = file(Layer::Household, "sam", r#""defaults":{"promptChars":600,"promptTerms":40}"#);
        let p = file(Layer::Personal, "sam", r#""defaults":{"promptChars":1200,"promptTerms":80}"#);
        let s = merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&p))], &[]);
        assert_eq!(s.defaults.prompt_chars_effective, 1200);
        assert_eq!(s.defaults.prompt_terms_effective, 80);
        let v = serde_json::to_value(&s.defaults).unwrap();
        assert_eq!(v["promptCharsEffective"], 1200);
        assert_eq!(v["promptTermsEffective"], 80);
    }

    #[test]
    fn a_snapshot_without_new_fields_keeps_its_id() {
        // 0.9.13 adds style fields, left out of the JSON when unset, so the
        // snapshot id a 0.9.12 install had is unchanged (no needless refresh).
        let p = file(Layer::Personal, "sam", r#""styles":[{"id":"work","label":"Work","prompt":"p","model":"m","expression":1}]"#);
        let s = merge("sam", &[(Layer::Personal, Some(&p))], &[]);
        let json = serde_json::to_value(&s.styles[0]).unwrap();
        let mut keys: Vec<&str> = json.as_object().unwrap().keys().map(String::as_str).collect();
        keys.sort();
        assert_eq!(keys, vec!["expression", "id", "label", "layer", "model", "prompt"]);
        assert!(serde_json::to_value(&s.defaults).unwrap().get("pinnedStyles").is_none());
    }

    #[test]
    fn higher_layer_wins_by_id() {
        let h = file(Layer::Household, "sam", r#""terms":[{"id":"zorb","text":"Zorb","priority":10}]"#);
        let p = file(Layer::Personal, "sam", r#""terms":[{"id":"zorb","text":"Zorbo","priority":90}]"#);
        let s = merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&p))], &[]);
        assert_eq!(s.terms.len(), 1);
        assert_eq!(s.terms[0].text, "Zorbo");
        assert_eq!(s.terms[0].layer, Layer::Personal);
    }

    #[test]
    fn app_style_edits_set_the_app_rule_field_by_field() {
        let personal = file(
            Layer::Personal,
            "tester",
            r#""styles":[{"id":"work","label":"Work","prompt":"p"},{"id":"casual","label":"Casual","prompt":"p"}],"apps":[{"id":"kite.exe","style":"work","terms":[]}]"#,
        );
        let less = op("a", "2026-01-01T00:00:00Z", r#""type":"app-style","app":"kite.exe","expression":1"#);
        let casual = op("b", "2026-01-02T00:00:00Z", r#""type":"app-style","app":"telegram.exe","style":"casual""#);
        let ghost = op("c", "2026-01-03T00:00:00Z", r#""type":"app-style","app":"slack.exe","style":"nope""#);
        let snap = merge("tester", &[(Layer::Personal, Some(&personal))], &[less.clone(), casual, ghost]);
        let rule = |id: &str| snap.apps.iter().find(|a| a["id"] == id).cloned().unwrap();
        assert_eq!(rule("kite.exe")["style"], "work", "the word lists' style stays");
        assert_eq!(rule("kite.exe")["expression"], 1);
        assert_eq!(rule("telegram.exe")["style"], "casual");
        assert!(snap.issues.iter().any(|i| i.code == "missing-style" && i.ids.contains(&"slack.exe".to_string())));
        // Undo: a tombstone removes the edit, the word lists' rule is back.
        let undo = op("d", "2026-01-04T00:00:00Z", r#""type":"tombstone","target":"dev1-a""#);
        let snap = merge("tester", &[(Layer::Personal, Some(&personal))], &[less, undo]);
        let kite = snap.apps.iter().find(|a| a["id"] == "kite.exe").unwrap();
        assert!(kite.get("expression").is_none());
    }

    #[test]
    fn app_style_edits_list_every_edit_and_the_lists_rule_under_them() {
        let personal = file(
            Layer::Personal,
            "tester",
            r#""styles":[{"id":"work","label":"Work","prompt":"p"}],"apps":[{"id":"kite.exe","style":"work","expression":1}]"#,
        );
        let a = op("a", "2026-01-01T00:00:00Z", r#""type":"app-style","app":"kite.exe","style":"vf-builtin-friends""#);
        let b = op("b", "2026-01-02T00:00:00Z", r#""type":"app-style","app":"kite.exe","expression":3"#);
        let c = op("c", "2026-01-03T00:00:00Z", r#""type":"app-style","app":"telegram.exe","style":"vf-pc-.v1.st.g_x""#);
        let snap = merge("tester", &[(Layer::Personal, Some(&personal))], &[a, b, c]);
        let rule = |id: &str| snap.apps.iter().find(|r| r["id"] == id).cloned().unwrap();
        let kite = rule("kite.exe");
        assert_eq!(kite["style"], "vf-builtin-friends");
        assert_eq!(kite["expression"], 3);
        assert_eq!(kite["editId"], "dev1-b");
        assert_eq!(kite["editIds"], serde_json::json!(["dev1-a", "dev1-b"]));
        assert_eq!(kite["listsRule"], serde_json::json!({ "style": "work", "expression": 1 }));
        assert_eq!(kite["expressionEditId"], "dev1-b", "the level is the edit's, not the lists'");
        assert_eq!(rule("telegram.exe")["listsRule"], Value::Null, "no word-list rule under it");
        assert!(rule("telegram.exe").get("expressionEditId").is_none());
        // Built-in and this-PC references are styles the app has: no issue.
        assert!(!snap.issues.iter().any(|i| i.code == "missing-style"), "{:?}", snap.issues);
        // A rule with no edits has neither field.
        let plain = merge("tester", &[(Layer::Personal, Some(&personal))], &[]);
        assert!(plain.apps[0].get("editIds").is_none() && plain.apps[0].get("listsRule").is_none());
    }

    #[test]
    fn device_style_refs_must_decode_canonically() {
        assert!(device_style_ref("vf-builtin-friends"));
        assert!(!device_style_ref("vf-builtin-pirate"), "not a built-in");
        assert!(device_style_ref("vf-pc-.v1.st.g.x.r8_.z5jd.hi6.b-my.t"));
        assert!(device_style_ref("vf-pc-abc"));
        assert!(!device_style_ref("vf-pc-"), "empty");
        assert!(!device_style_ref("vf-pc-ab."), "dangling escape");
        assert!(!device_style_ref("vf-pc-a.1"), "escape of a digit");
        assert!(!device_style_ref("vf-pc-a..b"));
        assert!(!device_style_ref(&format!("vf-pc-{}", "a".repeat(41))), "too long");
        assert!(!device_style_ref("friends"));
    }

    #[test]
    fn edits_win_and_tombstones_undo() {
        let p = file(Layer::Personal, "sam", r#""corrections":[{"id":"c1","from":["Zork"],"to":"Zorblax"}]"#);
        let edit = op("a", "2026-01-01T00:00:00Z", r#""type":"correction","from":["Zork"],"to":"Zorkon","scope":{"apps":["*"]}"#);
        let s = merge("sam", &[(Layer::Personal, Some(&p))], &[edit.clone()]);
        assert_eq!(s.corrections.len(), 1);
        assert_eq!(s.corrections[0].to, "Zorkon");
        assert!(s.issues.iter().any(|i| i.code == "correction-conflict"));
        let undo = op("b", "2026-01-02T00:00:00Z", r#""type":"tombstone","target":"dev1-a""#);
        let s = merge("sam", &[(Layer::Personal, Some(&p))], &[edit.clone(), undo.clone()]);
        assert_eq!(s.corrections[0].to, "Zorblax");
        // Undoing the undo brings the edit back.
        let redo = op("c", "2026-01-03T00:00:00Z", r#""type":"tombstone","target":"dev1-b""#);
        let s = merge("sam", &[(Layer::Personal, Some(&p))], &[edit, undo, redo]);
        assert_eq!(s.corrections[0].to, "Zorkon");
    }

    #[test]
    fn same_layer_conflict_newest_edit_wins() {
        let a = op("a", "2026-01-01T00:00:00Z", r#""type":"correction","from":["Zork"],"to":"Zorblax","scope":{"apps":["*"]}"#);
        let b = op("b", "2026-01-02T00:00:00Z", r#""type":"correction","from":["zork"],"to":"Zorkon","scope":{"apps":["*"]}"#);
        let s = merge("sam", &[], &[b.clone(), a.clone()]);
        assert_eq!(s.corrections.len(), 1);
        assert_eq!(s.corrections[0].to, "Zorkon");
        assert_eq!(s.issues[0].code, "correction-conflict");
    }

    #[test]
    fn scoped_override_keeps_lower_elsewhere() {
        let h = file(Layer::Household, "sam", r#""corrections":[{"id":"c1","from":["Phoebus"],"to":"Pip"}]"#);
        let p = file(
            Layer::Personal,
            "sam",
            r#""corrections":[{"id":"c2","from":["Phoebus"],"to":"Phoebus B.","scope":{"apps":["teamchat.exe"]}}]"#,
        );
        let s = merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&p))], &[]);
        // Both stay; the personal one is later so it wins inside Team Chat.
        assert_eq!(s.corrections.len(), 2);
        assert_eq!(s.corrections[1].id, "c2");
    }

    #[test]
    fn cross_layer_chains_and_cycles_are_resolved() {
        let h = file(Layer::Household, "sam", r#""corrections":[{"id":"c1","from":["Zork"],"to":"Zorblax"}]"#);
        let p = file(Layer::Personal, "sam", r#""corrections":[{"id":"c2","from":["Zorblax"],"to":"Zorkon"}]"#);
        let s = merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&p))], &[]);
        assert_eq!(s.corrections.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(), vec!["c2"]);
        assert!(s.issues.iter().any(|i| i.code == "correction-chain"));
        let cyc = file(Layer::Personal, "sam", r#""corrections":[{"id":"c3","from":["Zorblax"],"to":"Zork"}]"#);
        let s = merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&cyc))], &[]);
        assert_eq!(s.corrections.len(), 1);
        assert_eq!(s.corrections[0].id, "c3");
    }

    #[test]
    fn duplicate_text_keeps_higher_and_reports_in_layer() {
        let a = op("a", "2026-01-01T00:00:00Z", r#""type":"term","term":{"text":"Quibbit"}"#);
        let b = op("b", "2026-01-02T00:00:00Z", r#""type":"term","term":{"text":"quibbit"}"#);
        let s = merge("sam", &[], &[a, b]);
        assert_eq!(s.terms.len(), 1);
        assert_eq!(s.terms[0].id, "dev1-b");
        assert_eq!(s.issues[0].code, "duplicate-word");
    }

    #[test]
    fn references_are_checked() {
        let p = file(
            Layer::Personal,
            "sam",
            r#""defaults":{"cleanupStyle":"nope"},"apps":[{"id":"kite","terms":["ghost"]}]"#,
        );
        let s = merge("sam", &[(Layer::Personal, Some(&p))], &[]);
        assert!(s.defaults.values.cleanup_style.is_none());
        let codes: Vec<_> = s.issues.iter().map(|i| i.code.as_str()).collect();
        assert!(codes.contains(&"missing-style"));
        assert!(codes.contains(&"missing-word"));
    }

    #[test]
    fn word_rewritten_by_a_correction_is_reported() {
        let h = file(Layer::Household, "sam", r#""terms":[{"id":"phoebus","text":"Phoebus","kind":"person"}]"#);
        let p = file(Layer::Personal, "sam", r#""corrections":[{"id":"c1","from":["Phoebus"],"to":"Pip"}]"#);
        let s = merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&p))], &[]);
        assert!(s.issues.iter().any(|i| i.code == "word-corrected"));
        assert_eq!(s.terms.len(), 1);
        assert_eq!(s.corrections.len(), 1);
    }

    #[test]
    fn defaults_merge_field_by_field_and_id_is_stable() {
        let h = file(Layer::Household, "sam", r#""defaults":{"language":"en","promptTerms":20}"#);
        let p = file(Layer::Personal, "sam", r#""defaults":{"promptTerms":30}"#);
        let layers = [(Layer::Household, Some(&h)), (Layer::Personal, Some(&p))];
        let s = merge("sam", &layers, &[]);
        assert_eq!(s.defaults.values.language.as_deref(), Some("en"));
        assert_eq!(s.defaults.prompt_terms_effective, 30);
        assert_eq!(s.defaults.prompt_chars_effective, DEFAULT_PROMPT_CHARS);
        assert_eq!(s.defaults.from_layer["promptTerms"], Layer::Personal);
        assert_eq!(merge("sam", &layers, &[]).id, s.id);
        let p2 = file(Layer::Personal, "sam", r#""defaults":{"promptTerms":31}"#);
        assert_ne!(merge("sam", &[(Layer::Household, Some(&h)), (Layer::Personal, Some(&p2))], &[]).id, s.id);
    }
}
