package com.voiceflowkeyboard.ime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The built-in styles both apps ship, read from the MIT pack file
 * {@code pack/builtin/styles.json}. The build packages that file as a Java
 * resource at {@link #RESOURCE}, so the APK and the JVM unit tests read the
 * same bytes.
 */
final class BuiltinStyles {
    static final String RESOURCE = "/styles.json";
    static final String SCHEMA = "voiceflow-builtin-styles";

    static final class Style {
        final String id;
        final String name;
        final String icon;
        final String kind;
        final int expression;
        final String guidance;
        final String prompt;

        Style(String id, String name, String icon, String kind, int expression, String guidance, String prompt) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.kind = kind;
            this.expression = expression;
            this.guidance = guidance;
            this.prompt = prompt;
        }
    }

    private static volatile List<Style> cached;

    private BuiltinStyles() {
    }

    /** Every built-in style, in file order. Empty only if the resource is missing or broken. */
    static List<Style> all() {
        List<Style> styles = cached;
        if (styles == null) {
            styles = load();
            cached = styles;
        }
        return styles;
    }

    /** The built-in style ids (a word list's default style may name one). Empty when the file can't be read. */
    static java.util.Set<String> ids() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try {
            for (Style style : all()) {
                ids.add(style.id);
            }
        } catch (RuntimeException ignored) {
            // Only used to report a missing style.
        }
        return ids;
    }

    static Style find(String id) {
        for (Style style : all()) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return null;
    }

    private static List<Style> load() {
        try (InputStream in = BuiltinStyles.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Collections.emptyList();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return Collections.emptyList();
        }
    }

    /** Parses the pack file. A file that names another schema or version is refused whole. */
    static List<Style> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!SCHEMA.equals(root.optString("schema")) || root.optInt("version", -1) != 1) {
            throw new JSONException("not a voiceflow-builtin-styles v1 file");
        }
        JSONArray array = root.getJSONArray("styles");
        List<Style> styles = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String id = item.getString("id");
            if (!id.matches("[a-z][a-z0-9_]{0,39}")) {
                throw new JSONException("bad style id");
            }
            int expression = item.optInt("expression", Prefs.DEFAULT_EXPRESSION);
            if (expression < Prefs.EXPRESSION_RESERVED || expression > Prefs.EXPRESSION_EXPRESSIVE) {
                throw new JSONException("bad expression");
            }
            String prompt = item.getString("prompt").trim();
            if (prompt.isEmpty()) {
                throw new JSONException("empty prompt");
            }
            styles.add(new Style(
                    id,
                    item.getString("name").trim(),
                    item.optString("icon", ""),
                    item.optString("kind", "custom"),
                    expression,
                    item.optString("guidance", "").trim(),
                    prompt
            ));
        }
        return Collections.unmodifiableList(styles);
    }
}
