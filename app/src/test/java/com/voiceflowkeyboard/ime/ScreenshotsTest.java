package com.voiceflowkeyboard.ime;

import static org.junit.Assume.assumeFalse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collection;

import static org.robolectric.Shadows.shadowOf;

/**
 * Renders every keyboard state and app screen to PNG, in light and dark, at
 * phone width, the Fold's outer screen and its inner screen. Runs only when
 * {@code -PvfShots=<dir>} is given; otherwise every case is skipped.
 *
 * <p>Robolectric's native renderer is real Android drawing code (Skia), but it
 * is not a phone: fonts are the bundled Inter, there are no system bars, and
 * centred single-line text needs horizontal scrolling switched off to draw
 * (see {@link #prepareForCapture}).
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotsTest {
    private static final String DIR = System.getProperty("vf.shots", "");

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> devices() {
        return Arrays.asList(new Object[][]{
                {"phone-light", "w412dp-h915dp-notnight-xhdpi", 412, 915},
                {"phone-dark", "w412dp-h915dp-night-xhdpi", 412, 915},
                {"fold-outer-light", "w344dp-h882dp-notnight-xhdpi", 344, 882},
                {"fold-inner-light", "w840dp-h900dp-notnight-xhdpi", 840, 900},
                {"fold-inner-dark", "w840dp-h900dp-night-xhdpi", 840, 900},
        });
    }

    private final String device;
    private final String qualifiers;
    private final int widthDp;
    private final int heightDp;
    private ServiceController<TestService> controller;
    private TestService service;
    private View root;

    public ScreenshotsTest(String device, String qualifiers, int widthDp, int heightDp) {
        this.device = device;
        this.qualifiers = qualifiers;
        this.widthDp = widthDp;
        this.heightDp = heightDp;
    }

    public static class TestService extends VoiceFlowKeyboardService {
        BaseInputConnection connection;
        EditorInfo editor;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }

    @After public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
    }

    private void startKeyboard() {
        assumeFalse("screenshots run with -PvfShots=<dir>", DIR.isEmpty());
        RuntimeEnvironment.setQualifiers(qualifiers);
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        service.connection = new BaseInputConnection(new View(service), true);
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        info.imeOptions = EditorInfo.IME_ACTION_SEND;
        info.packageName = "com.example.chat";
        info.fieldId = 7;
        service.editor = info;
        root = service.onCreateInputView();
        service.onStartInput(info, false);
        service.onStartInputView(info, false);
        idle();
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Object call(String method) {
        return ReflectionHelpers.callInstanceMethod(service, method);
    }

    private void set(String field, Object value) {
        ReflectionHelpers.setField(service, field, value);
    }

    /** Puts dictated text into the field and remembers it, as a finished dictation does. */
    private void insert(String raw, String inserted, String preset) {
        String historyId = Prefs.addTranscriptHistory(service, raw, inserted, preset, Prefs.EXPRESSION_NATURAL,
                VoiceHistoryItem.OPERATION_DICTATION, "");
        service.connection.commitText(inserted, 1);
        ReflectionHelpers.callInstanceMethod(service, "rememberLastVoiceInsertion",
                ClassParameter.from(String.class, raw),
                ClassParameter.from(String.class, inserted),
                ClassParameter.from(String.class, preset),
                ClassParameter.from(int.class, Prefs.EXPRESSION_NATURAL),
                ClassParameter.from(String.class, VoiceHistoryItem.OPERATION_DICTATION),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(String.class, historyId));
    }

    @Test public void keyboardIdle() throws Exception {
        startKeyboard();
        shootKeyboard("keyboard-idle");
    }

    @Test public void keyboardSuggestions() throws Exception {
        startKeyboard();
        ReflectionHelpers.callInstanceMethod(service, "setPendingAutoComplete",
                ClassParameter.from(String.class, "Satu"),
                ClassParameter.from(java.util.List.class, Arrays.asList("Saturday", "Saturdays", "Saturn")));
        call("showIdleChips");
        shootKeyboard("keyboard-suggestions");
    }

    @Test public void keyboardListening() throws Exception {
        startKeyboard();
        set("recording", true);
        set("recordingStartedAt", System.currentTimeMillis() - 7_000);
        call("showCaptureRecordingState");
        call("setKeyboardLocked", true);
        WaveformView wave = ReflectionHelpers.getField(service, "waveform");
        wave.sample();
        TextView timer = ReflectionHelpers.getField(service, "timerText");
        timer.setText("0:07");
        shootKeyboard("keyboard-listening");
        call("showVoiceStyleOverlay");
        shootKeyboard("keyboard-listening-style-sheet");
        set("recording", false);
    }

    private void call(String method, boolean value) {
        ReflectionHelpers.callInstanceMethod(service, method, ClassParameter.from(boolean.class, value));
    }

    @Test public void keyboardWorking() throws Exception {
        startKeyboard();
        set("processing", true);
        call("showCaptureProcessingState");
        call("setKeyboardLocked", true);
        TextView status = ReflectionHelpers.getField(service, "statusText");
        status.setText("Tidying up: Clean…");
        shootKeyboard("keyboard-working");
        set("processing", false);
    }

    @Test public void keyboardInserted() throws Exception {
        startKeyboard();
        insert("so um are you guys still down for dinner on saturday like seven works or no wait seven thirty",
                "Are you guys still down for dinner on Saturday? I think seven thirty works.",
                Prefs.PRESET_CLEAN);
        call("showIdleChips");
        shootKeyboard("keyboard-inserted");
    }

    @Test public void keyboardKeptWords() throws Exception {
        startKeyboard();
        insert("I talked to my mom and 她说周末可能不来了 because her back is still hurting",
                "I talked to my mom and 她说周末可能不来了 because her back is still hurting",
                Prefs.PRESET_CLEAN);
        set("lastVoiceHeldBack", "I talked to my mom, and she said she might not come this weekend because her back still hurts.");
        call("showIdleChips");
        shootKeyboard("keyboard-kept-words");
    }

    @Test public void keyboardStyleSheet() throws Exception {
        startKeyboard();
        call("showVoiceStyleOverlay");
        shootKeyboard("keyboard-style-sheet");
        set("allStylesOpen", true);
        call("showVoiceStyleOverlay");
        shootKeyboard("keyboard-style-sheet-all");
    }

    @Test public void keyboardMessage() throws Exception {
        startKeyboard();
        ReflectionHelpers.callInstanceMethod(service, "setStatus",
                ClassParameter.from(String.class, "Not inserted: you moved to another field - saved in History"));
        shootKeyboard("keyboard-message");
    }

    @Test public void keyboardHistory() throws Exception {
        startKeyboard();
        Prefs.addTranscriptHistory(service, "quick update on the onboarding redesign", "Quick update on the onboarding redesign: the first pass of the sign-up flow is done. Can someone from backend look at Pinewood before Thursday?",
                Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.addTranscriptHistory(service, "running about twenty minutes late", "Running about twenty minutes late. Want me to grab coffee for Milo?",
                Prefs.PRESET_CASUAL, Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        call("showHistoryPanel");
        shootKeyboard("keyboard-history");
    }

    @Test public void keyboardSymbols() throws Exception {
        startKeyboard();
        call("toggleSymbolsMode");
        shootKeyboard("keyboard-symbols");
    }

    @Test public void keyboardEmoji() throws Exception {
        startKeyboard();
        call("showEmojiPicker");
        idle();
        shootKeyboard("keyboard-emoji");
    }

    // ------------------------------------------------------------------ screens

    @Test public void settingsHome() throws Exception {
        shootActivity("settings-home", new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class));
    }

    @Test public void settingsKeysAndModels() throws Exception {
        shootActivity("settings-keys-models", new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_MODELS));
    }

    @Test public void settingsKeyboard() throws Exception {
        shootActivity("settings-keyboard", new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_KEYBOARD));
    }

    @Test public void settingsStyles() throws Exception {
        shootActivity("settings-styles", new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PAGE_STYLES));
    }

    /** An enrolled phone with a downloaded update in the given state, for the update banner. */
    private void enrolWithUpdate(String state, long committedAt) throws Exception {
        android.content.Context app = RuntimeEnvironment.getApplication();
        javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance("AES");
        generator.init(256);
        javax.crypto.SecretKey key = generator.generateKey();
        SyncStore.keys = () -> key;
        SyncStore.enrol(app, "milo", "https://sync.pinewood.example", "vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        int installed = SelfUpdater.installedVersionCode(app);
        UpdatePolicy.Offer offer = new UpdatePolicy.Offer(installed + 1, "0.8.2",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 1000);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(SyncWorker.apkFile(app, offer.versionCode))) {
            out.write(new byte[1000]);
        }
        SyncStore.storePendingOffer(app, offer);
        long now = System.currentTimeMillis();
        SyncStore.updateEvidence(app, installed, e -> {
            e.lastCheckAt = now - 3 * 60_000L;
            e.pendingVersionCode = offer.versionCode;
            e.pendingState = state;
            e.lastInstallAttemptAt = committedAt;
        });
    }

    /** The home's update banner; the install itself is not run (the screen is drawn, not resumed into an install). */
    private void shootBanner(String name, String state, long committedAt) throws Exception {
        assumeFalse(DIR.isEmpty());
        RuntimeEnvironment.setQualifiers(qualifiers);
        enrolWithUpdate(state, committedAt);
        // Already tried this opening, so drawing the screen does not start an install.
        shootActivity(name, new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class), true);
    }

    @Test public void updateReady() throws Exception {
        shootBanner("settings-update-ready", UpdatePolicy.STATE_DOWNLOADED, 0);
    }

    @Test public void updateInstalling() throws Exception {
        shootBanner("settings-update-installing", UpdatePolicy.STATE_COMMITTED, System.currentTimeMillis() - 20_000L);
    }

    @Test public void updateWaitingForPlayProtect() throws Exception {
        shootBanner("settings-update-play-protect", UpdatePolicy.STATE_HELD, System.currentTimeMillis() - 4 * 60_000L);
    }

    @Test public void history() throws Exception {
        assumeFalse(DIR.isEmpty());
        RuntimeEnvironment.setQualifiers(qualifiers);
        android.content.Context app = RuntimeEnvironment.getApplication();
        Prefs.addTranscriptHistory(app, "quick update on the onboarding redesign", "Quick update on the onboarding redesign: the first pass of the sign-up flow is done, and email verification sometimes doesn't send the code. Can someone from backend look at Pinewood before Thursday?",
                Prefs.PRESET_BUSINESS, Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.addTranscriptHistory(app, "running about twenty minutes late", "Running about twenty minutes late. Want me to grab coffee for Febe?",
                Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.addTranscriptHistory(app, "the QBR deck needs the new numbers", "The QBR deck needs the new numbers by Friday.",
                Prefs.PRESET_CLEAN, Prefs.EXPRESSION_SUBTLE, VoiceHistoryItem.OPERATION_DICTATION, "");
        shootActivity("history", new Intent(app, HistoryActivity.class));
    }

    @Test public void wordLists() throws Exception {
        assumeFalse(DIR.isEmpty());
        RuntimeEnvironment.setQualifiers(qualifiers);
        android.content.Context app = RuntimeEnvironment.getApplication();
        Prefs.saveUserPhraseReplacements(app, Arrays.asList(
                new PhraseReplacement("Castrel\nKestral", "Kestrel", ""),
                new PhraseReplacement("Pinewood", "Pinewood", ""),
                new PhraseReplacement("Mylo", "Milo", ""),
                new PhraseReplacement("QBR", "QBR", "quarterly business review"),
                new PhraseReplacement("Feebee\nFebby", "Febe", "")
        ));
        shootActivity("word-lists", new Intent(app, FindReplaceActivity.class));
    }

    /** Word lists on an enrolled phone with an offline copy from the NAS (0.8.2). */
    @Test public void wordListsSynced() throws Exception {
        assumeFalse(DIR.isEmpty());
        RuntimeEnvironment.setQualifiers(qualifiers);
        android.content.Context app = RuntimeEnvironment.getApplication();
        javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance("AES");
        generator.init(256);
        javax.crypto.SecretKey key = generator.generateKey();
        SyncStore.keys = () -> key;
        Enrolment enrolment = SyncStore.enrol(app, "milo", "https://sync.pinewood.example",
                "vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        Prefs.saveUserPhraseReplacements(app, Arrays.asList(
                new PhraseReplacement("Castrel\nKestral", "Kestrel", ""),
                new PhraseReplacement("Glim", "Glim", "")));
        java.io.File dir = WordLists.folder(app, enrolment);
        new java.io.File(dir, "ops").mkdirs();
        WordLists.writeAtomically(new java.io.File(dir, "layer-household.json"), ("{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,"
                + "\"layer\":\"household\",\"user\":\"household\",\"terms\":[{\"id\":\"pinewood\",\"text\":\"Pinewood\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WordLists.writeAtomically(new java.io.File(dir, "layer-generated.json"), ("{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,"
                + "\"layer\":\"generated\",\"user\":\"milo\",\"terms\":[{\"id\":\"febe\",\"text\":\"Febe\",\"heardAs\":[\"Phoebe\"]},"
                + "{\"id\":\"qbr\",\"text\":\"QBR\"},{\"id\":\"zorblax\",\"text\":\"Zorblax\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WordLists.writeAtomically(new java.io.File(dir, "layer-personal.json"), ("{\"schema\":\"voiceflow-profile\",\"schemaVersion\":1,"
                + "\"layer\":\"personal\",\"user\":\"milo\",\"corrections\":[{\"id\":\"flumox\",\"from\":[\"Flu Mocks\"],\"to\":\"Flumox\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WordLists.State state = WordLists.State.empty();
        state.user = "milo";
        state.accepted = true;
        state.lastSyncAt = System.currentTimeMillis() - 4 * 60_000L;
        state.write(dir);
        WordLists.invalidate();
        shootActivity("word-lists-synced", new Intent(app, FindReplaceActivity.class));
    }

    // ------------------------------------------------------------------ capture

    private void shootKeyboard(String name) throws Exception {
        idle();
        int width = px(widthDp);
        prepareForCapture(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, width, root.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, root.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        save(name, bitmap);
    }

    private void shootActivity(String name, Intent intent) throws Exception {
        shootActivity(name, intent, false);
    }

    private void shootActivity(String name, Intent intent, boolean noInstall) throws Exception {
        assumeFalse("screenshots run with -PvfShots=<dir>", DIR.isEmpty());
        RuntimeEnvironment.setQualifiers(qualifiers);
        ActivityController<? extends Activity> activity = Robolectric.buildActivity(activityClass(intent), intent).create();
        if (noInstall) {
            // Mark the coming opening as already tried, so drawing it starts no install.
            int started = ReflectionHelpers.getStaticField(SettingsActivity.class, "startedScreens");
            long opening = ReflectionHelpers.getStaticField(SettingsActivity.class, "opening");
            ReflectionHelpers.setStaticField(SettingsActivity.class, "installTriedOpening",
                    started == 0 ? opening + 1 : opening);
        }
        activity.start().resume().visible();
        idle();
        View decor = activity.get().getWindow().getDecorView();
        int width = px(widthDp);
        int height = px(heightDp);
        prepareForCapture(decor);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        save(name, bitmap);
        activity.pause().stop().destroy();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Activity> activityClass(Intent intent) throws ClassNotFoundException {
        return (Class<? extends Activity>) Class.forName(intent.getComponent().getClassName());
    }

    private int px(int dp) {
        return Math.round(dp * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
    }

    /** The renderer quirk: centred single-line text only draws without horizontal scrolling. */
    private static void prepareForCapture(View view) {
        if (view instanceof TextView && !(view instanceof EditText)) {
            ((TextView) view).setHorizontallyScrolling(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                prepareForCapture(group.getChildAt(i));
            }
        }
    }

    private void save(String name, Bitmap bitmap) throws Exception {
        File dir = new File(DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File out = new File(dir, name + "-" + device + ".png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
