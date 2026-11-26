package com.voiceflowkeyboard.ime;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

final class PromptProfile {
    final String id;
    final String name;
    final String icon;
    /**
     * 0.9.3: the fields of its stored entry this version doesn't know (a newer version's),
     * as a JSON object's text, "" when there are none. Written back as they were, so
     * saving a style here never erases them.
     */
    final String extraJson;

    PromptProfile(String id, String name, String icon) {
        this(id, name, icon, "");
    }

    PromptProfile(String id, String name, String icon, String extraJson) {
        this.id = id;
        this.name = name;
        this.icon = icon == null ? "" : icon;
        this.extraJson = extraJson == null ? "" : extraJson;
    }

    /** The same entry with a new name and icon, its unknown fields kept. */
    PromptProfile renamed(String name, String icon) {
        return new PromptProfile(id, name, icon, extraJson);
    }

    String displayName() {
        return icon.isEmpty() ? name : icon + " " + name;
    }

    /** The fields of a stored entry other than id, name and icon, or "" when there are none. */
    static String extrasOf(JSONObject item) {
        JSONObject extra = new JSONObject();
        Iterator<String> keys = item.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("id".equals(key) || "name".equals(key) || "icon".equals(key)) {
                continue;
            }
            try {
                extra.put(key, item.get(key));
            } catch (JSONException ignored) {
                // a key that just came from this object is always readable
            }
        }
        return extra.length() == 0 ? "" : extra.toString();
    }

    /** The stored entry: its unknown fields as they were, then id, name and icon. */
    JSONObject toJson() throws JSONException {
        JSONObject item = new JSONObject();
        if (!extraJson.isEmpty()) {
            try {
                item = new JSONObject(extraJson);
            } catch (JSONException ignored) {
                item = new JSONObject(); // never lose the style over its extras
            }
        }
        item.put("id", id);
        item.put("name", name);
        item.put("icon", icon);
        return item;
    }
}
