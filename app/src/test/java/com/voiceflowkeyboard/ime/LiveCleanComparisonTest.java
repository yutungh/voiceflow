package com.voiceflowkeyboard.ime;

import static org.junit.Assume.assumeTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A manual, live comparison through the real dictation path (prompt composition,
 * OpenAI call, drift check, strict retry and fallback). Skipped unless
 * VF_LIVE_DIR is set; it is never run in CI.
 *
 * <p>VF_LIVE_DIR holds {@code transcripts.json} ([{id, text}]) and, optionally,
 * {@code friends-prompt.txt}, a stored Friends prompt to compare against
 * (with its expression in VF_LIVE_FRIENDS_EXPRESSION). The API key comes from
 * VF_LIVE_OPENAI_KEY and is only ever placed in the in-memory test settings.
 * Results go to {@code out.jsonl} in the same folder.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LiveCleanComparisonTest {
    @Test
    public void compareCleanWithFriends() throws Exception {
        String dir = System.getenv("VF_LIVE_DIR");
        String key = System.getenv("VF_LIVE_OPENAI_KEY");
        assumeTrue(dir != null && !dir.isEmpty() && key != null && !key.isEmpty());

        Context context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        Prefs.saveApiKeys(context, key, "", "", "");
        Prefs.promptProfiles(context);
        File friends = new File(dir, "friends-prompt.txt");
        int friendsExpression = Prefs.EXPRESSION_NATURAL;
        if (friends.isFile()) {
            String stored = new String(Files.readAllBytes(friends.toPath()), StandardCharsets.UTF_8);
            Prefs.shared(context).edit().putString("prompt_casual", stored).commit();
            String level = System.getenv("VF_LIVE_FRIENDS_EXPRESSION");
            friendsExpression = level == null ? Prefs.EXPRESSION_NATURAL : Integer.parseInt(level.trim());
        }

        JSONArray transcripts = new JSONArray(new String(
                Files.readAllBytes(new File(dir, "transcripts.json").toPath()), StandardCharsets.UTF_8));
        try (FileOutputStream out = new FileOutputStream(new File(dir, "out.jsonl"))) {
            for (int i = 0; i < transcripts.length(); i++) {
                JSONObject item = transcripts.getJSONObject(i);
                String spoken = item.getString("text");
                out.write(row(context, item.getString("id"), "clean-natural", spoken,
                        Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL).getBytes(StandardCharsets.UTF_8));
                out.write(row(context, item.getString("id"), "friends-stored", spoken,
                        Prefs.PRESET_CASUAL, friendsExpression).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static String row(Context context, String id, String variant, String spoken, String preset, int expression)
            throws Exception {
        long start = System.currentTimeMillis();
        JSONObject row = new JSONObject().put("id", id).put("variant", variant).put("spoken", spoken);
        try {
            TransformClient.Outcome outcome = TransformClient.transformChecked(context, spoken, preset, expression);
            row.put("output", outcome.text)
                    .put("fellBack", outcome.fellBack())
                    .put("reason", outcome.fallbackReason)
                    .put("retried", outcome.retried)
                    .put("heldBack", outcome.heldBack)
                    .put("mixed", outcome.mixed);
        } catch (Exception e) {
            row.put("error", e.getClass().getSimpleName());
        }
        row.put("ms", System.currentTimeMillis() - start);
        return row.toString() + "\n";
    }
}
