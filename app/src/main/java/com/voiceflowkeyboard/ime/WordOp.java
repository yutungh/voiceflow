package com.voiceflowkeyboard.ime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One edit made in an app ({@code voiceflow-op} v1, pack/README.md): an
 * immutable file. Undo is a tombstone that targets an edit; a tombstone can be
 * undone too. Written for this app from the contract in {@code pack/}.
 *
 * <p>{@link #parse} returns null for a file that is not a usable edit (bad JSON,
 * wrong schema, a type this app does not know): such a file is skipped, never an
 * error that empties anything.
 */
final class WordOp {
    static final String CORRECTION = "correction";
    static final String TERM = "term";
    static final String APP_STYLE = "app-style";
    static final String TOMBSTONE = "tombstone";
    static final int MAX_OP_BYTES = 64 * 1024;

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}$");
    private static final Pattern DEVICE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    final String id;
    final String device;
    /** The instant of {@code createdAt}, offsets honoured, to the nanosecond. */
    final java.time.Instant createdAt;
    final String type;
    /** For a term or a correction: the entry it adds. */
    final WordLayer.Term term;
    final WordLayer.Correction correction;
    /** For a tombstone: the edit it removes. */
    final String target;

    private WordOp(String id, String device, java.time.Instant createdAt, String type, WordLayer.Term term,
                   WordLayer.Correction correction, String target) {
        this.id = id;
        this.device = device;
        this.createdAt = createdAt;
        this.type = type;
        this.term = term;
        this.correction = correction;
        this.target = target;
    }

    static WordOp parse(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_OP_BYTES) {
            return null;
        }
        try {
            return parse(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (JSONException e) {
            return null;
        }
    }

    static WordOp parse(JSONObject json) {
        try {
            if (!"voiceflow-op".equals(json.opt("schema"))) {
                return null;
            }
            Object version = json.opt("version");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1) {
                return null;
            }
            String id = string(json, "id");
            String device = string(json, "device");
            if (id == null || !ID.matcher(id).matches() || device == null || !DEVICE.matcher(device).matches()) {
                return null;
            }
            java.time.Instant created = instant(string(json, "createdAt"));
            String type = string(json, "type");
            if (created == null || type == null) {
                return null;
            }
            switch (type) {
                case TOMBSTONE: {
                    String target = string(json, "target");
                    return target == null || target.isEmpty() ? null
                            : new WordOp(id, device, created, type, null, null, target);
                }
                case CORRECTION: {
                    List<String> from = words(json.opt("from"), 1, Integer.MAX_VALUE);
                    String to = string(json, "to");
                    JSONObject scope = json.optJSONObject("scope");
                    List<String> apps = scope == null ? null : apps(scope.opt("apps"));
                    if (from == null || !WordLayer.validWord(to) || apps == null) {
                        return null;
                    }
                    return new WordOp(id, device, created, type, null,
                            new WordLayer.Correction(id, from, to, apps, WordLayer.EDIT), null);
                }
                case TERM: {
                    JSONObject term = json.optJSONObject("term");
                    if (term == null) {
                        return null;
                    }
                    String text = string(term, "text");
                    if (!WordLayer.validWord(text)) {
                        return null;
                    }
                    List<String> heard = term.has("heardAs") ? words(term.opt("heardAs"), 0, Integer.MAX_VALUE)
                            : new ArrayList<>();
                    String gloss = term.has("gloss") ? string(term, "gloss") : "";
                    String kind = term.has("kind") ? string(term, "kind") : "";
                    if (heard == null || gloss == null || gloss.codePointCount(0, gloss.length()) > 80 || kind == null) {
                        return null;
                    }
                    int priority = 50;
                    if (term.has("priority")) {
                        Object p = term.opt("priority");
                        if (!(p instanceof Number)) {
                            return null;
                        }
                        double value = ((Number) p).doubleValue();
                        if (value != Math.rint(value) || value < 0 || value > 100) {
                            return null;
                        }
                        priority = (int) value;
                    }
                    List<String> apps = term.has("apps") ? apps(term.opt("apps"))
                            : Collections.singletonList(WordLayer.EVERY_APP);
                    if (apps == null) {
                        return null;
                    }
                    return new WordOp(id, device, created, type,
                            new WordLayer.Term(id, text, heard, kind, gloss, priority, apps, WordLayer.EDIT), null, null);
                }
                case APP_STYLE:
                    // Styles reach the phone in a later release; the edit is kept but not used.
                    return new WordOp(id, device, created, type, null, null, null);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** ISO 8601 with an offset or Z; null when it can't be read. */
    static java.time.Instant instant(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            // No offset at all: the contract says UTC.
            try {
                return java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String string(JSONObject json, String key) {
        Object value = json.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static List<String> words(Object value, int min, int max) {
        if (!(value instanceof JSONArray)) {
            return null;
        }
        JSONArray array = (JSONArray) value;
        if (array.length() < min || array.length() > max) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String) || !WordLayer.validWord((String) item)) {
                return null;
            }
            out.add((String) item);
        }
        return out;
    }

    private static List<String> apps(Object value) {
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() < 1) {
            return null;
        }
        JSONArray array = (JSONArray) value;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String) || ((String) item).isEmpty()) {
                return null;
            }
            String app = (String) item;
            out.add(WordLayer.EVERY_APP.equals(app) ? app : app.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
