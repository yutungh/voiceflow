package com.voiceflowkeyboard.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Offline Unicode catalog; independent of Android so search and sequences can be tested. */
final class EmojiCatalog {
    static final class Entry {
        final String emoji;
        final String category;
        final String name;
        final String base;
        final String searchable;
        final String normalizedName;
        final boolean skinTone;

        Entry(String[] fields) {
            emoji = fields[0];
            category = fields[1];
            name = fields[2];
            base = fields[4];
            searchable = normalize(name + " " + fields[3]);
            normalizedName = normalize(name);
            skinTone = emoji.codePoints().anyMatch(cp -> cp >= 0x1f3fb && cp <= 0x1f3ff);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byEmoji = new LinkedHashMap<>();
    private final Map<String, List<Entry>> families = new LinkedHashMap<>();
    private final List<String> categories = new ArrayList<>();

    static EmojiCatalog read(Reader source) throws IOException {
        EmojiCatalog catalog = new EmojiCatalog();
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 5) throw new IOException("Invalid emoji catalog row");
            Entry entry = new Entry(fields);
            if (catalog.byEmoji.put(entry.emoji, entry) != null) {
                throw new IOException("Duplicate emoji in catalog");
            }
            catalog.entries.add(entry);
            catalog.families.computeIfAbsent(entry.base, key -> new ArrayList<>()).add(entry);
            if (!catalog.categories.contains(entry.category)) catalog.categories.add(entry.category);
        }
        if (catalog.entries.isEmpty()) throw new IOException("Empty emoji catalog");
        return catalog;
    }

    List<String> categories() {
        return Collections.unmodifiableList(categories);
    }

    Entry find(String emoji) {
        return byEmoji.get(emoji);
    }

    List<Entry> variants(Entry entry) {
        return Collections.unmodifiableList(families.get(entry.base));
    }

    List<Entry> search(String query, String category) {
        String normalized = normalize(query);
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        boolean includeTones = normalized.contains("skin") || normalized.contains("tone");
        List<Entry> exact = new ArrayList<>();
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.emoji.equals(query.trim())) {
                exact.add(entry);
                continue;
            }
            if (entry.skinTone && !includeTones) continue;
            if (terms.length == 0 && category != null && !category.equals(entry.category)) continue;
            boolean matchesAll = true;
            for (String term : terms) {
                if (!entry.searchable.contains(term)) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) {
                if (entry.normalizedName.equals(normalized)) exact.add(entry);
                else matches.add(entry);
            }
        }
        exact.addAll(matches);
        return exact;
    }

    /** Delete a complete known emoji (including ZWJ, flag, and skin-tone sequences). */
    int deletionLength(CharSequence beforeCursor) {
        if (beforeCursor == null || beforeCursor.length() == 0) return 0;
        String text = beforeCursor.toString();
        int length = Character.charCount(Character.codePointBefore(text, text.length()));
        for (Entry entry : entries) {
            if (entry.emoji.length() > length && text.endsWith(entry.emoji)) {
                length = entry.emoji.length();
            }
        }
        return length;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[-_:|]+", " ")
                .trim().replaceAll("\\s+", " ");
    }
}
