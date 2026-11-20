package com.voiceflowkeyboard.ime;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class EmojiCatalogTest {
    private static EmojiCatalog catalog;

    @BeforeClass public static void loadCatalog() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(
                new File("src/main/assets/emoji/catalog.tsv")), StandardCharsets.UTF_8)) {
            catalog = EmojiCatalog.read(reader);
        }
    }

    @Test public void catalogHasAllNineCategoriesAndFullSequences() {
        assertEquals(9, catalog.categories().size());
        assertTrue(catalog.search("", null).size() > 1800);
        for (String category : catalog.categories()) {
            List<EmojiCatalog.Entry> entries = catalog.search("", category);
            assertFalse(category, entries.isEmpty());
            for (EmojiCatalog.Entry entry : entries) assertEquals(category, entry.category);
        }
        assertNotNull(catalog.find("👨‍👩‍👧‍👦"));
        assertNotNull(catalog.find("🇺🇸"));
        assertNotNull(catalog.find("1️⃣"));
    }

    @Test public void keywordSearchCrossesCategoriesAndRanksExactNamesFirst() {
        assertContains("happy", "😊");
        assertContains("birthday", "🎂");
        assertContains("dog", "🐶");
        assertContains("  RED_heart  ", "❤️");
        assertContains("united states", "🇺🇸");
        assertEquals("🐕", catalog.search("dog", "Food & Drink").get(0).emoji);
        assertTrue(catalog.search("no-such-emoji-zzzz", null).isEmpty());
        assertEquals("👍🏽", catalog.search("👍🏽", null).get(0).emoji);
    }

    @Test public void skinTonesAreGroupedAndExplicitlySearchable() {
        EmojiCatalog.Entry thumb = catalog.find("👍");
        assertEquals(6, catalog.variants(thumb).size());
        assertTrue(catalog.variants(thumb).stream().anyMatch(e -> "👍🏽".equals(e.emoji)));
        assertFalse(catalog.search("thumbs up", null).stream().anyMatch(e -> e.skinTone));
        assertTrue(catalog.search("thumbs up medium skin tone", null).stream().anyMatch(e -> "👍🏽".equals(e.emoji)));
    }

    @Test public void deletionPreservesSurrogatesAndRemovesWholeEmojiSequences() {
        for (String emoji : new String[]{"😀", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇺🇸", "1️⃣", "🏳️‍🌈", "👩🏾‍💻"}) {
            assertEquals(emoji, emoji.length(), catalog.deletionLength("hello " + emoji));
            assertEquals(emoji, emoji.length(), catalog.deletionLength("😀" + emoji));
        }
        assertEquals(1, catalog.deletionLength("😀x"));
        assertEquals(2, catalog.deletionLength("𐐀"));
        assertEquals(0, catalog.deletionLength(""));
        assertEquals(0, catalog.deletionLength(null));
    }

    private void assertContains(String query, String emoji) {
        assertTrue(query, catalog.search(query, null).stream().anyMatch(e -> emoji.equals(e.emoji)));
    }
}
