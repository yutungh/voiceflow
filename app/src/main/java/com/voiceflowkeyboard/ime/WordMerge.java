package com.voiceflowkeyboard.ime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merges word-list layers and edits into one snapshot, by the rules in
 * pack/README.md ("Layers and precedence", "Checked again after merging").
 * Written for this app from that contract; it passes the shared vectors in
 * {@code pack/vectors/}.
 *
 * <p>Precedence, lowest first: built-in defaults, household, generated,
 * personal, this phone's own list, edits. Within a file layer a later entry is
 * higher; edits are ordered by {@code createdAt}, then id. Pure: no Android.
 */
final class WordMerge {
    static final String ISSUE_CONFLICT = "correction-conflict";
    static final String ISSUE_CHAIN = "correction-chain";
    static final String ISSUE_WORD_CORRECTED = "word-corrected";
    static final String ISSUE_MISSING_STYLE = "missing-style";
    static final String ISSUE_MISSING_WORD = "missing-word";
    static final String ISSUE_DUPLICATE_WORD = "duplicate-word";

    static final int DEFAULT_PROMPT_CHARS = 600;
    static final int DEFAULT_PROMPT_TERMS = 40;

    private WordMerge() {
    }

    /** The merged word lists one dictation uses. Immutable. */
    static final class Snapshot {
        /** Words in precedence order, lowest first (this phone's rows in their own order). */
        final List<WordLayer.Term> terms;
        /** Corrections in precedence order, lowest first. */
        final List<WordLayer.Correction> corrections;
        final List<String> issues;
        /** Defaults: highest file layer that sets each field. */
        final Map<String, Object> defaults;
        final String id;
        /**
         * This phone's own words, as its list has them, in its order: they go first
         * into the prompt budget even when an edit elsewhere lists the same word.
         */
        final List<WordLayer.Term> phoneTerms;
        /**
         * 0.9.3: the word lists' styles, one per id (a higher layer's entry replaces a
         * lower one's whole), in the order each id first appears.
         */
        final List<WordLayer.Style> styles;

        Snapshot(List<WordLayer.Term> terms, List<WordLayer.Correction> corrections, List<String> issues,
                 Map<String, Object> defaults) {
            this(terms, corrections, issues, defaults, Collections.emptyList());
        }

        Snapshot(List<WordLayer.Term> terms, List<WordLayer.Correction> corrections, List<String> issues,
                 Map<String, Object> defaults, List<WordLayer.Term> phoneTerms) {
            this(terms, corrections, issues, defaults, phoneTerms, Collections.emptyList());
        }

        Snapshot(List<WordLayer.Term> terms, List<WordLayer.Correction> corrections, List<String> issues,
                 Map<String, Object> defaults, List<WordLayer.Term> phoneTerms, List<WordLayer.Style> styles) {
            this.phoneTerms = Collections.unmodifiableList(new ArrayList<>(phoneTerms));
            this.styles = Collections.unmodifiableList(new ArrayList<>(styles));
            this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
            this.corrections = Collections.unmodifiableList(new ArrayList<>(corrections));
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
            this.defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
            this.id = computeId(this.terms, this.corrections, this.defaults, this.styles);
        }

        int promptChars() {
            Object value = defaults.get("promptChars");
            return value instanceof Integer ? (Integer) value : DEFAULT_PROMPT_CHARS;
        }

        int promptTerms() {
            Object value = defaults.get("promptTerms");
            return value instanceof Integer ? (Integer) value : DEFAULT_PROMPT_TERMS;
        }

        /** The corrections in force in {@code app}, highest precedence first (the order they are applied in). */
        List<WordLayer.Correction> correctionsFor(String app) {
            List<WordLayer.Correction> out = new ArrayList<>();
            for (int i = corrections.size() - 1; i >= 0; i--) {
                WordLayer.Correction c = corrections.get(i);
                if (WordLayer.appliesTo(c.apps, app)) {
                    out.add(c);
                }
            }
            return out;
        }

        int count(String layer) {
            int n = 0;
            for (WordLayer.Term term : terms) {
                if (term.layer.equals(layer)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * Merges {@code layers} (any of the five layer kinds, lowest first; a rejected
     * file is simply left out) with the edits in {@code ops} (any order, duplicates
     * allowed). {@code knownStyles}: the style ids the app itself has, besides the
     * layers' own and "raw".
     */
    static Snapshot merge(List<WordLayer> layers, List<WordOp> ops, Set<String> knownStyles) {
        Set<String> issues = new TreeSet<>();
        List<WordLayer> ordered = new ArrayList<>(layers);
        ordered.add(editsLayer(ops, issues));

        // Words: the same id in a higher layer replaces the entry.
        LinkedHashMap<String, WordLayer.Term> byId = new LinkedHashMap<>();
        for (WordLayer layer : ordered) {
            for (WordLayer.Term term : layer.terms) {
                byId.remove(term.id);
                byId.put(term.id, term);
            }
        }
        // The same word (ignoring case and spaces) is listed once: the highest wins.
        LinkedHashMap<String, List<WordLayer.Term>> byText = new LinkedHashMap<>();
        for (WordLayer.Term term : byId.values()) {
            byText.computeIfAbsent(WordLayer.norm(term.text), k -> new ArrayList<>()).add(term);
        }
        List<WordLayer.Term> terms = new ArrayList<>();
        for (List<WordLayer.Term> same : byText.values()) {
            terms.add(same.get(same.size() - 1));
            Set<String> layersSeen = new HashSet<>();
            for (WordLayer.Term term : same) {
                if (!layersSeen.add(term.layer)) {
                    issues.add(ISSUE_DUPLICATE_WORD);
                }
            }
        }

        // Corrections: same id replaces; then conflicts, then chains.
        LinkedHashMap<String, WordLayer.Correction> correctionsById = new LinkedHashMap<>();
        for (WordLayer layer : ordered) {
            for (WordLayer.Correction correction : layer.corrections) {
                correctionsById.remove(correction.id);
                correctionsById.put(correction.id, correction);
            }
        }
        List<WordLayer.Correction> corrections = new ArrayList<>(correctionsById.values());
        // Indexed by heard form, so the cost follows the lists' size, not its square.
        Map<String, List<Integer>> byForm = new HashMap<>();
        for (int i = 0; i < corrections.size(); i++) {
            Set<String> seen = new HashSet<>();
            for (String form : corrections.get(i).from) {
                String key = WordLayer.norm(form);
                if (seen.add(key)) {
                    byForm.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
                }
            }
        }
        for (int i = 0; i < corrections.size(); i++) {
            WordLayer.Correction lower = corrections.get(i);
            List<String> forms = new ArrayList<>(lower.from);
            Set<String> taken = new HashSet<>();
            for (String form : lower.from) {
                String key = WordLayer.norm(form);
                for (int j : byForm.get(key)) {
                    if (j <= i) {
                        continue;
                    }
                    WordLayer.Correction higher = corrections.get(j);
                    if (!WordLayer.overlap(lower.apps, higher.apps)) {
                        continue;
                    }
                    // The same heard form written the same way higher up: redundant, not a conflict.
                    if (!lower.to.equals(higher.to)) {
                        issues.add(ISSUE_CONFLICT);
                    }
                    if (WordLayer.covers(higher.apps, lower.apps)) {
                        taken.add(key);
                    }
                }
            }
            forms.removeIf(form -> taken.contains(WordLayer.norm(form)));
            corrections.set(i, forms.isEmpty() ? null : forms.size() == lower.from.size() ? lower : lower.withFrom(forms));
        }
        // Chains, highest first: a correction that a kept one would change again, or
        // that would change a kept one's result, is set aside.
        List<WordLayer.Correction> accepted = new ArrayList<>();
        Map<String, List<WordLayer.Correction>> keptForms = new HashMap<>();
        Map<String, List<WordLayer.Correction>> keptPieces = new HashMap<>();
        for (int i = corrections.size() - 1; i >= 0; i--) {
            WordLayer.Correction candidate = corrections.get(i);
            if (candidate == null) {
                continue;
            }
            Set<String> pieces = WordLayer.pieces(WordLayer.norm(candidate.to));
            Set<String> chainForms = new HashSet<>();
            for (String form : candidate.from) {
                String key = WordLayer.norm(form);
                if (!WordLayer.selfForm(candidate, key)) {
                    chainForms.add(key);
                }
            }
            boolean chained = false;
            for (String piece : pieces) {
                if (chainsWithAny(candidate, keptForms.get(piece))) {
                    chained = true;
                    break;
                }
            }
            if (!chained) {
                for (String form : chainForms) {
                    if (chainsWithAny(candidate, keptPieces.get(form))) {
                        chained = true;
                        break;
                    }
                }
            }
            if (chained) {
                issues.add(ISSUE_CHAIN);
                continue;
            }
            accepted.add(candidate);
            for (String form : chainForms) {
                keptForms.computeIfAbsent(form, k -> new ArrayList<>()).add(candidate);
            }
            for (String piece : pieces) {
                keptPieces.computeIfAbsent(piece, k -> new ArrayList<>()).add(candidate);
            }
        }
        Collections.reverse(accepted);

        // A word a fix would rewrite: both kept, reported. A fix that only
        // re-spells its own result (this phone's capitalisation rows) rewrites nothing.
        Map<String, List<WordLayer.Correction>> fixesByForm = new HashMap<>();
        for (WordLayer.Correction correction : accepted) {
            for (String form : correction.from) {
                String key = WordLayer.norm(form);
                if (!WordLayer.selfForm(correction, key)) {
                    fixesByForm.computeIfAbsent(key, k -> new ArrayList<>()).add(correction);
                }
            }
        }
        for (WordLayer.Term term : terms) {
            for (WordLayer.Correction correction : fixesByForm.getOrDefault(WordLayer.norm(term.text), Collections.emptyList())) {
                if (WordLayer.overlap(term.apps, correction.apps)) {
                    issues.add(ISSUE_WORD_CORRECTED);
                    break;
                }
            }
        }

        // Defaults, field by field, from the highest layer that sets each.
        Map<String, Object> defaults = new LinkedHashMap<>();
        Set<String> styles = new HashSet<>(knownStyles);
        styles.add("raw");
        Map<String, WordLayer.AppRule> apps = new LinkedHashMap<>();
        LinkedHashMap<String, WordLayer.Style> stylesById = new LinkedHashMap<>();
        for (WordLayer layer : ordered) {
            defaults.putAll(layer.defaults);
            styles.addAll(layer.styleIds);
            apps.putAll(layer.apps);
            for (WordLayer.Style style : layer.styles) {
                stylesById.put(style.id, style);
            }
        }
        Object cleanupStyle = defaults.get("cleanupStyle");
        if (cleanupStyle instanceof String && !styles.contains(cleanupStyle)) {
            issues.add(ISSUE_MISSING_STYLE);
            defaults.remove("cleanupStyle");
        }
        Set<String> termIds = new HashSet<>();
        for (WordLayer.Term term : terms) {
            termIds.add(term.id);
        }
        for (WordLayer.AppRule rule : apps.values()) {
            if (!rule.style.isEmpty() && !styles.contains(rule.style)) {
                issues.add(ISSUE_MISSING_STYLE);
            }
            for (String ref : rule.terms) {
                if (!termIds.contains(ref)) {
                    issues.add(ISSUE_MISSING_WORD);
                }
            }
        }

        // Precedence order, each word where its winning entry stands.
        Map<WordLayer.Term, Integer> position = new java.util.IdentityHashMap<>();
        int index = 0;
        for (WordLayer.Term term : byId.values()) {
            position.put(term, index++);
        }
        terms.sort(Comparator.comparingInt(position::get));
        List<WordLayer.Term> phoneTerms = new ArrayList<>();
        for (WordLayer layer : layers) {
            if (WordLayer.PHONE.equals(layer.layer)) {
                phoneTerms.addAll(layer.terms);
            }
        }
        // A style whose winning entry has a blank label or prompt is offered nowhere.
        List<WordLayer.Style> offered = new ArrayList<>();
        for (WordLayer.Style style : stylesById.values()) {
            if (!style.label.isEmpty() && !style.prompt.isEmpty()) {
                offered.add(style);
            }
        }
        return new Snapshot(terms, accepted, new ArrayList<>(issues), defaults, phoneTerms, offered);
    }

    /**
     * {@code candidate} and one of {@code kept} would chain in an app they share.
     * This phone's own rows were always applied together, once, in one pass; a
     * chain between two of them is kept as it always was.
     */
    private static boolean chainsWithAny(WordLayer.Correction candidate, List<WordLayer.Correction> kept) {
        if (kept == null) {
            return false;
        }
        for (WordLayer.Correction other : kept) {
            boolean bothPhone = WordLayer.PHONE.equals(candidate.layer) && WordLayer.PHONE.equals(other.layer);
            if (!bothPhone && WordLayer.overlap(candidate.apps, other.apps)
                    && (WordLayer.chains(candidate, other) || WordLayer.chains(other, candidate))) {
                return true;
            }
        }
        return false;
    }

    /** A tombstone that names an edit that is not here. */
    static final String ISSUE_ORPHAN_TOMBSTONE = "orphan-tombstone";

    /**
     * The edits as one layer: valid edits, each id once, ordered by time then id.
     * Tombstones are applied newest first; a tombstone already removed by a newer
     * one does nothing, so undoing an undo brings the edit back.
     */
    static WordLayer editsLayer(List<WordOp> ops, Set<String> issues) {
        List<WordOp> sorted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<WordOp> input = new ArrayList<>();
        for (WordOp op : ops) {
            if (op != null) {
                input.add(op);
            }
        }
        input.sort(Comparator.<WordOp, java.time.Instant>comparing(op -> op.createdAt).thenComparing(op -> op.id));
        for (WordOp op : input) {
            if (seen.add(op.id)) {
                sorted.add(op);
            }
        }
        Set<String> removed = new HashSet<>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            WordOp op = sorted.get(i);
            if (removed.contains(op.id) || !WordOp.TOMBSTONE.equals(op.type)) {
                continue;
            }
            if (!seen.contains(op.target)) {
                issues.add(ISSUE_ORPHAN_TOMBSTONE);
            }
            removed.add(op.target);
        }
        List<WordLayer.Term> terms = new ArrayList<>();
        List<WordLayer.Correction> corrections = new ArrayList<>();
        for (WordOp op : sorted) {
            if (removed.contains(op.id)) {
                continue;
            }
            if (op.term != null) {
                terms.add(op.term);
            } else if (op.correction != null) {
                corrections.add(op.correction);
            }
        }
        return new WordLayer(WordLayer.EDIT, "", terms, corrections, new HashSet<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private static String computeId(List<WordLayer.Term> terms, List<WordLayer.Correction> corrections,
                                    Map<String, Object> defaults, List<WordLayer.Style> styles) {
        StringBuilder canonical = new StringBuilder();
        for (WordLayer.Term t : terms) {
            canonical.append("t\u0001").append(t.id).append('\u0001').append(t.text).append('\u0001')
                    .append(String.join("\u0002", t.heardAs)).append('\u0001').append(t.gloss).append('\u0001')
                    .append(t.priority).append('\u0001').append(String.join("\u0002", t.apps)).append('\u0001')
                    .append(t.layer).append('\n');
        }
        for (WordLayer.Correction c : corrections) {
            canonical.append("c\u0001").append(c.id).append('\u0001').append(String.join("\u0002", c.from))
                    .append('\u0001').append(c.to).append('\u0001').append(String.join("\u0002", c.apps))
                    .append('\u0001').append(c.layer).append('\n');
        }
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(defaults).entrySet()) {
            canonical.append("d\u0001").append(e.getKey()).append('\u0001').append(e.getValue()).append('\n');
        }
        // 0.9.3: styles only when there are any, so a snapshot without them keeps its id.
        for (WordLayer.Style s : styles) {
            canonical.append("s\u0001").append(s.id).append('\u0001').append(s.label).append('\u0001').append(s.prompt)
                    .append('\u0001').append(s.model).append('\u0001').append(s.expression).append('\u0001')
                    .append(s.layer).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return GatewayClient.hex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(canonical.toString().hashCode());
        }
    }
}
