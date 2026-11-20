package com.voiceflowkeyboard.ime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One layer of a user's word lists, as the {@code voiceflow-profile} v1 contract
 * in {@code pack/} describes it (pack/README.md, pack/schema). Written for this
 * app from that contract. A file is accepted whole or not at all:
 * {@link #parse} either returns the layer or throws {@link Rejected} with a short
 * reason code (never a word from the file).
 *
 * <p>Besides the three file layers there are two the phone makes itself: the
 * built-in fixes ({@link #DEFAULTS}) and this phone's own list ({@link #PHONE}),
 * which sits above the personal file and below the edits.
 */
final class WordLayer {
    static final String DEFAULTS = "defaults";
    static final String HOUSEHOLD = "household";
    static final String GENERATED = "generated";
    static final String PERSONAL = "personal";
    static final String PHONE = "phone";
    static final String EDIT = "edit";

    /** The file layers, lowest first. */
    static final List<String> FILE_LAYERS = Collections.unmodifiableList(Arrays.asList(HOUSEHOLD, GENERATED, PERSONAL));

    static final int MAX_LAYER_BYTES = 1024 * 1024;
    static final String EVERY_APP = "*";

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}$");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    /** Characters a word may never hold: angle brackets, control and format characters. */
    private static final Pattern UNSAFE = Pattern.compile(
            "[<>\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u2028-\\u202e\\u2066-\\u2069\\ufeff]");
    private static final Pattern APP = Pattern.compile("^(\\*|[^A-Z<>\\r\\n]{1,200})$");
    /** Never {@code (?U)}: Android's ICU engine refuses it (see {@link WhiteSpace}). */
    private static final Pattern WHITESPACE = WhiteSpace.RUN;
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(
            "person", "bot", "project", "org", "product", "acronym", "place", "term"));

    private static final Set<String> TOP_KEYS = keys("$schema", "schema", "schemaVersion", "layer", "user",
            "updatedAt", "updatedBy", "note", "terms", "corrections", "styles", "defaults", "privacy", "apps");
    private static final Set<String> TERM_KEYS = keys("id", "text", "heardAs", "kind", "gloss", "priority", "apps", "note");
    private static final Set<String> CORRECTION_KEYS = keys("id", "from", "to", "scope", "note");
    private static final Set<String> SCOPE_KEYS = keys("apps");
    private static final Set<String> STYLE_KEYS = keys("id", "label", "prompt", "model", "expression", "note");
    private static final Set<String> DEFAULT_KEYS = keys("transcriptionModel", "language", "cleanupModel",
            "cleanupStyle", "cleanupTimeoutS", "promptChars", "promptTerms");
    private static final Set<String> PRIVACY_KEYS = keys("blockApps", "titleDenyRe");

    /** A file that is not accepted. {@link #reason} is a short code, safe for the status file. */
    static final class Rejected extends Exception {
        final String reason;

        Rejected(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** A word to listen for: a hint only, it never rewrites text. */
    static final class Term {
        final String id;
        final String text;
        final List<String> heardAs;
        final String kind;
        final String gloss;
        final int priority;
        final List<String> apps;
        final String layer;

        Term(String id, String text, List<String> heardAs, String kind, String gloss, int priority,
             List<String> apps, String layer) {
            this.id = id;
            this.text = text;
            this.heardAs = Collections.unmodifiableList(new ArrayList<>(heardAs));
            this.kind = kind == null ? "" : kind;
            this.gloss = gloss == null ? "" : gloss;
            this.priority = priority;
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            this.layer = layer;
        }
    }

    /** A spelling always fixed: these heard forms are written {@link #to}. */
    static final class Correction {
        final String id;
        final List<String> from;
        final String to;
        final List<String> apps;
        final String layer;

        Correction(String id, List<String> from, String to, List<String> apps, String layer) {
            this.id = id;
            this.from = Collections.unmodifiableList(new ArrayList<>(from));
            this.to = to;
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            this.layer = layer;
        }

        Correction withFrom(List<String> forms) {
            return new Correction(id, forms, to, apps, layer);
        }
    }

    final String layer;
    final String updatedAt;
    final List<Term> terms;
    final List<Correction> corrections;
    final Set<String> styleIds;
    /** Defaults this layer sets: name to String or Integer. */
    final Map<String, Object> defaults;
    /** App rules: app id to its style reference ("" when none) and word references. */
    final Map<String, AppRule> apps;

    static final class AppRule {
        final String style;
        final List<String> terms;

        AppRule(String style, List<String> terms) {
            this.style = style == null ? "" : style;
            this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
        }
    }

    WordLayer(String layer, String updatedAt, List<Term> terms, List<Correction> corrections, Set<String> styleIds,
              Map<String, Object> defaults, Map<String, AppRule> apps) {
        this.layer = layer;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
        this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
        this.corrections = Collections.unmodifiableList(new ArrayList<>(corrections));
        this.styleIds = Collections.unmodifiableSet(new HashSet<>(styleIds));
        this.defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        this.apps = Collections.unmodifiableMap(new LinkedHashMap<>(apps));
    }

    static WordLayer empty(String layer) {
        return new WordLayer(layer, "", new ArrayList<>(), new ArrayList<>(), new HashSet<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    // ------------------------------------------------------------------ parsing

    static WordLayer parse(byte[] bytes, String expectedLayer, String user) throws Rejected {
        if (bytes == null) {
            throw new Rejected("missing");
        }
        if (bytes.length > MAX_LAYER_BYTES) {
            throw new Rejected("too-large");
        }
        JSONObject json;
        try {
            json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new Rejected("not-json");
        }
        return parse(json, expectedLayer, user);
    }

    static WordLayer parse(JSONObject json, String expectedLayer, String user) throws Rejected {
        onlyKeys(json, TOP_KEYS, "unknown-field");
        if (!"voiceflow-profile".equals(json.opt("schema"))) {
            throw new Rejected("wrong-schema");
        }
        Object version = json.opt("schemaVersion");
        if (!(version instanceof Number) || !isInteger((Number) version)) {
            throw new Rejected("bad-version");
        }
        if (((Number) version).doubleValue() != 1) {
            throw new Rejected(((Number) version).doubleValue() > 1 ? "newer-version" : "bad-version");
        }
        if (!expectedLayer.equals(json.opt("layer"))) {
            throw new Rejected("wrong-layer");
        }
        Object owner = json.opt("user");
        if (!(owner instanceof String) || !NAME.matcher((String) owner).matches()) {
            throw new Rejected("bad-user");
        }
        String wantedUser = HOUSEHOLD.equals(expectedLayer) ? HOUSEHOLD : user;
        if (!owner.equals(wantedUser)) {
            throw new Rejected("wrong-user");
        }
        optString(json, "updatedAt", -1);
        optString(json, "updatedBy", -1);
        optString(json, "note", 500);

        List<Term> terms = new ArrayList<>();
        Set<String> termIds = new HashSet<>();
        for (JSONObject item : objects(json, "terms")) {
            Term term = parseTerm(item, expectedLayer);
            if (!termIds.add(term.id)) {
                throw new Rejected("duplicate-id");
            }
            terms.add(term);
        }
        List<Correction> corrections = new ArrayList<>();
        Set<String> correctionIds = new HashSet<>();
        for (JSONObject item : objects(json, "corrections")) {
            Correction correction = parseCorrection(item, expectedLayer);
            if (!correctionIds.add(correction.id)) {
                throw new Rejected("duplicate-id");
            }
            corrections.add(correction);
        }
        Set<String> styleIds = new HashSet<>();
        for (JSONObject item : objects(json, "styles")) {
            onlyKeys(item, STYLE_KEYS, "unknown-field");
            String id = requiredId(item);
            requiredString(item, "label", 1, 40);
            requiredString(item, "prompt", 1, 8000);
            optString(item, "model", -1);
            optInteger(item, "expression", 0, 4);
            optString(item, "note", 500);
            if (!styleIds.add(id)) {
                throw new Rejected("duplicate-id");
            }
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (json.has("defaults")) {
            JSONObject d = object(json.opt("defaults"));
            onlyKeys(d, DEFAULT_KEYS, "unknown-field");
            for (String key : new String[]{"transcriptionModel", "language", "cleanupModel", "cleanupStyle"}) {
                String value = optString(d, key, -1);
                if (value != null) {
                    defaults.put(key, value);
                }
            }
            putInteger(defaults, "cleanupTimeoutS", optInteger(d, "cleanupTimeoutS", 1, 120));
            putInteger(defaults, "promptChars", optInteger(d, "promptChars", 50, 4000));
            putInteger(defaults, "promptTerms", optInteger(d, "promptTerms", 1, 500));
        }
        if (json.has("privacy")) {
            JSONObject privacy = object(json.opt("privacy"));
            onlyKeys(privacy, PRIVACY_KEYS, "unknown-field");
            for (String app : strings(privacy, "blockApps", 0, 50)) {
                if (app.isEmpty() || !app.equals(app.toLowerCase(Locale.ROOT)) || hasUpper(app)) {
                    throw new Rejected("bad-app");
                }
            }
            for (String pattern : strings(privacy, "titleDenyRe", 0, 50)) {
                if (pattern.length() > 200) {
                    throw new Rejected("too-long");
                }
            }
        }
        Map<String, AppRule> apps = new LinkedHashMap<>();
        for (JSONObject item : objects(json, "apps")) {
            String id = requiredId(item);
            String style = optString(item, "style", -1);
            List<String> refs = new ArrayList<>();
            if (item.has("terms")) {
                JSONArray array = array(item.opt("terms"));
                for (int i = 0; i < array.length(); i++) {
                    Object ref = array.opt(i);
                    if (!(ref instanceof String) || !ID.matcher((String) ref).matches()) {
                        throw new Rejected("bad-id");
                    }
                    refs.add((String) ref);
                }
            }
            if (apps.put(id, new AppRule(style, refs)) != null) {
                throw new Rejected("duplicate-id");
            }
        }
        checkCorrectionsWithinFile(corrections);
        return new WordLayer(expectedLayer, (String) json.opt("updatedAt"), terms, corrections, styleIds, defaults, apps);
    }

    private static Term parseTerm(JSONObject item, String layer) throws Rejected {
        onlyKeys(item, TERM_KEYS, "unknown-field");
        String id = requiredId(item);
        String text = word(item.opt("text"));
        List<String> heard = new ArrayList<>();
        if (item.has("heardAs")) {
            JSONArray array = array(item.opt("heardAs"));
            if (array.length() > 50) {
                throw new Rejected("too-many");
            }
            for (int i = 0; i < array.length(); i++) {
                heard.add(word(array.opt(i)));
            }
        }
        String kind = optString(item, "kind", -1);
        if (kind != null && !KINDS.contains(kind)) {
            throw new Rejected("bad-kind");
        }
        String gloss = optString(item, "gloss", 80);
        if (gloss != null && UNSAFE.matcher(gloss).find()) {
            throw new Rejected("unsafe-text");
        }
        Integer priority = optInteger(item, "priority", 0, 100);
        List<String> apps = item.has("apps") ? apps(item.opt("apps")) : Collections.singletonList(EVERY_APP);
        optString(item, "note", 500);
        return new Term(id, text, heard, kind, gloss, priority == null ? 50 : priority, apps, layer);
    }

    private static Correction parseCorrection(JSONObject item, String layer) throws Rejected {
        onlyKeys(item, CORRECTION_KEYS, "unknown-field");
        String id = requiredId(item);
        JSONArray from = array(item.opt("from"));
        if (from.length() < 1 || from.length() > 50) {
            throw new Rejected(from.length() < 1 ? "empty-list" : "too-many");
        }
        List<String> forms = new ArrayList<>();
        for (int i = 0; i < from.length(); i++) {
            forms.add(word(from.opt(i)));
        }
        String to = word(item.opt("to"));
        List<String> apps = Collections.singletonList(EVERY_APP);
        if (item.has("scope")) {
            JSONObject scope = object(item.opt("scope"));
            onlyKeys(scope, SCOPE_KEYS, "unknown-field");
            if (!scope.has("apps")) {
                throw new Rejected("missing-field");
            }
            apps = apps(scope.opt("apps"));
        }
        optString(item, "note", 500);
        return new Correction(id, forms, to, apps, layer);
    }

    /**
     * Conflicting or chained corrections inside one file reject it. Indexed by
     * heard form, so a large file costs about its size, not its size squared.
     */
    private static void checkCorrectionsWithinFile(List<Correction> corrections) throws Rejected {
        Map<String, List<Integer>> byForm = new java.util.HashMap<>();
        Map<String, List<Integer>> byChainForm = new java.util.HashMap<>();
        for (int i = 0; i < corrections.size(); i++) {
            Correction c = corrections.get(i);
            String self = norm(c.to);
            Set<String> seen = new HashSet<>();
            for (String form : c.from) {
                String key = norm(form);
                if (!seen.add(key)) {
                    continue;
                }
                byForm.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
                if (!key.equals(self)) {
                    byChainForm.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
                }
            }
        }
        for (List<Integer> same : byForm.values()) {
            if (same.size() < 2) {
                continue;
            }
            for (int x = 0; x < same.size(); x++) {
                for (int y = x + 1; y < same.size(); y++) {
                    Correction a = corrections.get(same.get(x));
                    Correction b = corrections.get(same.get(y));
                    if (!a.to.equals(b.to) && overlap(a.apps, b.apps)) {
                        throw new Rejected("correction-conflict");
                    }
                }
            }
        }
        for (int i = 0; i < corrections.size(); i++) {
            Correction a = corrections.get(i);
            for (String piece : pieces(norm(a.to))) {
                for (int j : byChainForm.getOrDefault(piece, java.util.Collections.emptyList())) {
                    if (j != i && overlap(a.apps, corrections.get(j).apps)) {
                        throw new Rejected("correction-chain");
                    }
                }
            }
        }
    }

    /**
     * Every stretch of {@code text} that starts and ends at a word edge (no letter
     * or digit just outside it): the only places a heard form can match whole.
     */
    static Set<String> pieces(String text) {
        Set<String> out = new HashSet<>();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && Character.isLetterOrDigit(text.codePointBefore(i))) {
                continue;
            }
            for (int j = i + 1; j <= length && j - i <= 240; j++) {
                if (j < length && Character.isLetterOrDigit(text.codePointAt(j))) {
                    continue;
                }
                out.add(text.substring(i, j));
            }
        }
        return out;
    }

    /** A heard form that is only the correction's own result, re-spelled: it never changes another result. */
    static boolean selfForm(Correction c, String normForm) {
        return normForm.equals(norm(c.to));
    }

    // ------------------------------------------------------------------ shared rules

    /** Trimmed, runs of white space as one space, lower case: how words and heard forms are compared. */
    static String norm(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Two app lists share at least one app ("*" shares every app). */
    static boolean overlap(List<String> a, List<String> b) {
        if (a.contains(EVERY_APP) || b.contains(EVERY_APP)) {
            return true;
        }
        for (String app : a) {
            if (b.contains(app)) {
                return true;
            }
        }
        return false;
    }

    /** {@code outer} reaches every app {@code inner} does. */
    static boolean covers(List<String> outer, List<String> inner) {
        if (outer.contains(EVERY_APP)) {
            return true;
        }
        return !inner.contains(EVERY_APP) && outer.containsAll(inner);
    }

    static boolean appliesTo(List<String> apps, String app) {
        return apps.contains(EVERY_APP) || (app != null && !app.isEmpty() && apps.contains(app.toLowerCase(Locale.ROOT)));
    }

    /** One heard form written two ways. */
    static boolean conflicts(Correction a, Correction b) {
        return !a.to.equals(b.to) && sharesForm(a, b);
    }

    /** The two share at least one heard form (ignoring case and runs of spaces). */
    static boolean sharesForm(Correction a, Correction b) {
        for (String form : a.from) {
            String key = norm(form);
            for (String other : b.from) {
                if (key.equals(norm(other))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Applying {@code b} to {@code a}'s result would change it again. A form of
     * {@code b} that only re-spells {@code b}'s own result does not count.
     */
    static boolean chains(Correction a, Correction b) {
        String result = norm(a.to);
        for (String form : b.from) {
            String key = norm(form);
            if (!selfForm(b, key) && containsWholeWord(result, key)) {
                return true;
            }
        }
        return false;
    }

    /** {@code needle} occurs in {@code haystack} with no letter or digit right before or after it. */
    static boolean containsWholeWord(String haystack, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int end = at + needle.length();
            boolean before = at == 0 || !Character.isLetterOrDigit(haystack.codePointBefore(at));
            boolean after = end >= haystack.length() || !Character.isLetterOrDigit(haystack.codePointAt(end));
            if (before && after) {
                return true;
            }
            from = at + 1;
        }
    }

    /** A word as the contract allows it: 1-60 characters, nothing unsafe. */
    static boolean validWord(String value) {
        if (value == null) {
            return false;
        }
        int length = value.codePointCount(0, value.length());
        return length >= 1 && length <= 60 && !UNSAFE.matcher(value).find();
    }

    private static String word(Object value) throws Rejected {
        if (!(value instanceof String)) {
            throw new Rejected("bad-word");
        }
        if (!validWord((String) value)) {
            throw new Rejected(UNSAFE.matcher((String) value).find() ? "unsafe-text" : "bad-word");
        }
        return (String) value;
    }

    private static List<String> apps(Object value) throws Rejected {
        JSONArray array = array(value);
        if (array.length() < 1 || array.length() > 50) {
            throw new Rejected(array.length() < 1 ? "empty-list" : "too-many");
        }
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object app = array.opt(i);
            if (!(app instanceof String) || !APP.matcher((String) app).matches()) {
                throw new Rejected("bad-app");
            }
            apps.add(EVERY_APP.equals(app) ? EVERY_APP : ((String) app).toLowerCase(Locale.ROOT));
        }
        return apps;
    }

    private static boolean hasUpper(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 'A' && value.charAt(i) <= 'Z') {
                return true;
            }
        }
        return false;
    }

    private static Set<String> keys(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static void onlyKeys(JSONObject json, Set<String> allowed, String reason) throws Rejected {
        Iterator<String> it = json.keys();
        while (it.hasNext()) {
            if (!allowed.contains(it.next())) {
                throw new Rejected(reason);
            }
        }
    }

    private static String requiredId(JSONObject item) throws Rejected {
        Object id = item.opt("id");
        if (!(id instanceof String) || !ID.matcher((String) id).matches()) {
            throw new Rejected("bad-id");
        }
        return (String) id;
    }

    private static void requiredString(JSONObject item, String key, int min, int max) throws Rejected {
        Object value = item.opt(key);
        if (!(value instanceof String)) {
            throw new Rejected("missing-field");
        }
        int length = ((String) value).codePointCount(0, ((String) value).length());
        if (length < min || length > max) {
            throw new Rejected("too-long");
        }
    }

    /** The string at {@code key}, null when absent; {@code max} < 0 means no length limit. */
    private static String optString(JSONObject item, String key, int max) throws Rejected {
        if (!item.has(key)) {
            return null;
        }
        Object value = item.opt(key);
        if (!(value instanceof String)) {
            throw new Rejected("bad-type");
        }
        if (max >= 0 && ((String) value).codePointCount(0, ((String) value).length()) > max) {
            throw new Rejected("too-long");
        }
        return (String) value;
    }

    private static Integer optInteger(JSONObject item, String key, int min, int max) throws Rejected {
        if (!item.has(key)) {
            return null;
        }
        Object value = item.opt(key);
        if (!(value instanceof Number) || !isInteger((Number) value)) {
            throw new Rejected("bad-type");
        }
        double number = ((Number) value).doubleValue();
        if (number < min || number > max) {
            throw new Rejected("out-of-range");
        }
        return (int) number;
    }

    private static void putInteger(Map<String, Object> map, String key, Integer value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static boolean isInteger(Number number) {
        if (number instanceof Integer || number instanceof Long) {
            return true;
        }
        double value = number.doubleValue();
        return !Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value);
    }

    private static JSONObject object(Object value) throws Rejected {
        if (!(value instanceof JSONObject)) {
            throw new Rejected("bad-type");
        }
        return (JSONObject) value;
    }

    private static JSONArray array(Object value) throws Rejected {
        if (!(value instanceof JSONArray)) {
            throw new Rejected("bad-type");
        }
        return (JSONArray) value;
    }

    private static List<JSONObject> objects(JSONObject json, String key) throws Rejected {
        List<JSONObject> out = new ArrayList<>();
        if (!json.has(key)) {
            return out;
        }
        JSONArray array = array(json.opt(key));
        for (int i = 0; i < array.length(); i++) {
            out.add(object(array.opt(i)));
        }
        return out;
    }

    private static List<String> strings(JSONObject json, String key, int min, int max) throws Rejected {
        List<String> out = new ArrayList<>();
        if (!json.has(key)) {
            return out;
        }
        JSONArray array = array(json.opt(key));
        if (array.length() < min || array.length() > max) {
            throw new Rejected("too-many");
        }
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String)) {
                throw new Rejected("bad-type");
            }
            out.add((String) value);
        }
        return out;
    }
}
