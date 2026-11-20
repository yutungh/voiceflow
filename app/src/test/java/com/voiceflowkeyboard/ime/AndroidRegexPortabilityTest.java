package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's regular expressions must compile on Android, whose engine is ICU, not the JDK's.
 * These tests run on the JVM (Robolectric too uses the JDK engine), so a pattern that only
 * the JDK accepts passes every other test and then throws on the phone. That is what 0.8.2
 * did: {@code "(?U)\\s+"} in WordLayer and WordPrompt threw PatternSyntaxException in a
 * static initialiser, every sync run died with ExceptionInInitializerError, and the phone
 * never reported to the NAS or read its word lists.
 */
public class AndroidRegexPortabilityTest {
    /** An inline flag group naming UNICODE_CHARACTER_CLASS, on or off: (?U), (?iU), (?i-U), (?U: ...). ICU refuses each. */
    private static final Pattern INLINE_U = Pattern.compile("\\(\\?[idmsuxU-]*U[idmsuxU-]*[):]");
    /** Pattern flags Android documents as unsupported. */
    private static final Pattern UNSUPPORTED_FLAG = Pattern.compile("\\b(UNICODE_CHARACTER_CLASS|CANON_EQ)\\b");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    public void noPatternUsesFlagsAndroidRefuses() throws IOException {
        List<File> sources = new ArrayList<>();
        collect(new File("src/main/java"), sources);
        assertTrue("found the app's sources", sources.size() > 50);
        List<String> problems = new ArrayList<>();
        for (File source : sources) {
            List<String> lines = Files.readAllLines(source.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String problem = problem(lines.get(i));
                if (problem != null) {
                    problems.add(source.getName() + ":" + (i + 1) + " " + problem);
                }
            }
        }
        assertEquals("regex features Android's ICU engine refuses (use WhiteSpace.RUN for white space)",
                new ArrayList<String>(), problems);
    }

    @Test
    public void theScanCatchesWhat082Shipped() {
        assertEquals("(?U) flag", problem("    private static final Pattern WHITESPACE = Pattern.compile(\"(?U)\\\\s+\");"));
        assertEquals("(?U) flag", problem("Pattern p = Pattern.compile(\"(?iU)x\");"));
        assertEquals("(?U) flag", problem("Pattern p = Pattern.compile(\"(?i-U)x\");"));
        assertEquals("(?U) flag", problem("Pattern p = Pattern.compile(\"(?-U:x)\");"));
        assertEquals("UNICODE_CHARACTER_CLASS", problem("Pattern.compile(\"\\\\s+\", Pattern.UNICODE_CHARACTER_CLASS);"));
        assertEquals(null, problem("Pattern.compile(\"(?i)x\");"));
        assertEquals(null, problem("Pattern.compile(\"(?:ab)+\");"));
        assertEquals(null, problem(" * Never {@code (?U)}: Android refuses it."));
    }

    @Test
    public void whiteSpaceMatchesWhatTheJdksUnicodeClassMatched() {
        // The JDK's (?U)\s is what 0.8.2 meant; WhiteSpace.RUN must match exactly the same characters.
        Pattern jdkUnicode = Pattern.compile("(?U)\\s");
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            String s = new String(Character.toChars(cp));
            assertEquals("U+" + Integer.toHexString(cp), jdkUnicode.matcher(s).matches(),
                    WhiteSpace.RUN.matcher(s).matches());
        }
        assertFalse(WhiteSpace.RUN.matcher("​").matches());
    }

    @Test
    public void wordsAreComparedWithUnicodeSpacesFolded() {
        assertEquals("milo febe", WordLayer.norm("  Milo 　 Febe\t"));
        assertEquals("zee drul", WordLayer.norm("Zee Drul"));
        assertEquals("QBR hint", WordPrompt.promptWord(" QBR  hint "));
    }

    private static String problem(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return null;
        }
        Matcher literal = STRING_LITERAL.matcher(line);
        while (literal.find()) {
            if (INLINE_U.matcher(literal.group()).find()) {
                return "(?U) flag";
            }
        }
        Matcher flag = UNSUPPORTED_FLAG.matcher(STRING_LITERAL.matcher(line).replaceAll("\"\""));
        return flag.find() ? flag.group(1) : null;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().endsWith(".java") || child.getName().endsWith(".kt")) {
                out.add(child);
            }
        }
    }
}
