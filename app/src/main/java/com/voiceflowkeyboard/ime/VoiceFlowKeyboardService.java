package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceFlowKeyboardService extends InputMethodService {
    private static final int KEY_VISUAL_GAP_DP = 3;
    private static final int CORRECTION_DELAY_MS = 120;
    private static final int SPACE_CURSOR_HOLD_MS = 280;
    // Holding delete removes characters, then whole words from 3 s, then
    // everything before the cursor from 6 s.
    private static final long DELETE_HOLD_WORDS_MS = 3000;
    private static final long DELETE_HOLD_CLEAR_MS = 6000;
    // The number pad's function column matches the delete key's width.
    private static final float NUMPAD_FUNCTION_WEIGHT = 1.35f;
    private static final int NUMPAD_MAX_WIDTH_DP = 440;
    // How long after a copy the Paste chip is offered, outside password fields.
    private static final long PASTE_CHIP_FRESH_MS = 2 * 60 * 1000;
    private static final String PASTE_CHIP_TAG = "paste-chip";
    private static final int SPACE_CURSOR_STEP_DP = 10;
    private static final Map<String, String> COMMON_TYPOS = commonTypos();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /**
     * Separate from {@link #executor} on purpose. That one is a single thread
     * shared with cloud transcription, retone, transform and model downloads;
     * a queued download runs for minutes and would head-of-line block every
     * keystroke's correction behind it.
     */
    private final ExecutorService typingExecutor = Executors.newSingleThreadExecutor();
    /**
     * The daily check that selected cloud models still exist. Its own thread,
     * because a lookup can block for tens of seconds on a slow network and
     * must never hold up dictation on {@link #executor}.
     */
    private final ExecutorService maintenanceExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TextView> keyButtons = new ArrayList<>();
    private final List<TextView> letterButtons = new ArrayList<>();

    private KeyColors colors;
    /** The shared app and keyboard palette (desktop B3.1), light or dark with the system. */
    private Palette ui;
    private FrameLayout keyboardSurface;
    private LinearLayout keyboardPanel;
    private EmojiPickerView emojiPicker;
    private EmojiCatalog emojiCatalog;
    private boolean emojiCatalogLoading;
    private LinearLayout voiceStyleOverlay;
    private LinearLayout chipStrip;
    private HorizontalScrollView chipScroller;
    private View voiceStyleScroller;
    private TextView statusText;
    private ImageButton translationButton;
    private ImageButton createButton;
    private ImageButton instructionButton;
    private ImageButton spellcheckButton;
    private ImageButton micButton;
    private View cancelRecordingButton;
    private ImageButton topHistoryButton;
    private TextView shiftButton;
    /** Space bars in the current layout: one normally, two when split. */
    private final List<TextView> spaceButtons = new ArrayList<>();
    private boolean recording;
    private boolean processing;
    private boolean translationCapture;
    private boolean creationCapture;
    private boolean instructionCapture;
    private boolean shift;
    private boolean autoShift;
    private boolean capsLock;
    private boolean symbolsMode;
    /** Which page the symbols layer shows while {@link #symbolsMode} is on. */
    private enum SymbolPage { PRIMARY, MORE, NUMPAD }

    private SymbolPage symbolPage = SymbolPage.PRIMARY;
    // The Paste chip works from the clipboard's description, cached here so a
    // keystroke never touches the clipboard. The contents are read only when
    // the chip is tapped.
    // A clip is identified by the system's copy timestamp; pasteOfferClip is -1
    // when there is no text to offer.
    private long pasteOfferClip = -1;
    private long pasteOfferCopiedAt;
    private long pasteHandledClip = -1;
    private boolean pasteOfferInPasswordField;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private final Runnable pasteExpiryRunnable = this::expirePasteChip;
    // A pending "model unavailable" notice, kept until tapped or until the
    // keyboard hides after showing it.
    private String modelNotice = "";
    private boolean modelNoticeShown;
    // The field the current layer was chosen for; null after the keyboard
    // hides, so the next field always decides afresh.
    private String layerFieldKey;
    private int activeInputType;
    private boolean historyMode;
    private InputMode inputMode = InputMode.ENGLISH;
    private PinyinEngine pinyinEngine;
    private PinyinSession pinyinSession;
    private boolean deleteHeld;
    private boolean spaceCursorMode;
    private boolean offlineRecordingSession;
    private String offlineRecordingProvider = Prefs.PROVIDER_OFFLINE_VOSK;
    private float downX;
    private float downY;
    private float rootDownX;
    private float rootDownY;
    private float spaceCursorLastStepX;
    private boolean rootSwipeConsumed;
    private boolean rootSwipeTracking;
    private boolean rootSwipeStartedOnSpace;
    private boolean rootSwipeStartedOnExpressionSlider;
    private boolean rootSwipeStartedOnVoiceStyleScroller;
    private boolean panelAnimating;
    private boolean retoneMode;
    private boolean allStylesOpen;
    private long deleteHoldStartMs;
    private long lastShiftTapMs;
    /** The style and expression for the next or current message; the only copy. */
    private final StyleChoice choice = new StyleChoice(Prefs.PRESET_CASUAL, Prefs.DEFAULT_EXPRESSION);
    private String lastVoiceRawTranscript = "";
    private String lastVoiceInsertedText = "";
    private String lastVoicePreset = "";
    private int lastVoiceExpression = Prefs.DEFAULT_EXPRESSION;
    private String lastVoiceOperation = VoiceHistoryItem.OPERATION_DICTATION;
    private String lastVoiceTargetLanguage = "";
    private String lastVoiceHistoryId = "";
    private int lastVoiceSelectionEnd = -1;
    // The field the last insertion went into, and the tidy-up the drift check held
    // back for it ("Use cleaned"); empty when there is none.
    private DeliveryGuard.Target lastVoiceEditor = null;
    private String lastVoiceHeldBack = "";
    /** Why {@link #lastVoiceHeldBack} is on offer: mixed languages, or the user chose Original. */
    private String lastVoiceHeldBackReason = HELD_BACK_MIXED;
    // The bar above the keys (VoiceBar decides which one shows).
    private static final int BAR_HEIGHT_DP = 52;
    /** The recording panel's content stays this wide at most (a Fold's inner screen). */
    private static final int PANEL_MAX_WIDTH_DP = 600;
    private static final String HELD_BACK_MIXED = "mixed";
    private static final String HELD_BACK_ORIGINAL = "original";
    private static final int MENU_TRANSLATE = 1;
    private static final int MENU_CREATE = 2;
    private static final int MENU_INSTRUCTION = 3;
    private static final int MENU_SPELLING = 4;
    private static final int MENU_PASTE = 5;
    private static final int MENU_SETTINGS = 6;
    private static final int MENU_CHINESE_LAYOUT = 7;
    private boolean compactBar;
    private VoiceBar.Mode barMode = VoiceBar.Mode.IDLE;
    private FrameLayout barHost;
    private LinearLayout idleBar;
    private LinearLayout suggestionBar;
    private LinearLayout listeningBar;
    private LinearLayout workingBar;
    private LinearLayout doneBar;
    private LinearLayout headerBar;
    private TextView styleChip;
    private TextView messageText;
    private TextView dictateButton;
    private ImageButton overflowButton;
    private TextView listeningLabel;
    private TextView timerText;
    private WaveformView waveform;
    private View recordDot;
    private TextView stopButton;
    private android.widget.ImageView workingIcon;
    private android.widget.ImageView doneIcon;
    private LinearLayout doneLabels;
    private TextView doneTitle;
    private TextView doneSubtitle;
    private LinearLayout doneActions;
    private TextView headerTitle;
    private TextView headerAction;
    /** 0.8.6: the recording panel, in the keys' place while a dictation records and is processed. */
    private FrameLayout recordingPanel;
    /** The panel's first line: what the chips are for, or what to say for a voice edit. */
    private TextView recordingHintText;
    /** The panel's expression slider (the sheet's is {@link #expressionSlider}). */
    private SeekBar panelSlider;
    /** True from a capture's start until its text is inserted, or it is cancelled or fails. */
    private boolean capturePanel;
    /** A style picked from More in this recording keeps the fifth chip (desktop's "kept"). */
    private String panelKeptStyle;
    /** The next opening of the style sheet starts with All styles open (the panel's More). */
    private boolean openAllStylesNext;
    /** The current input start was a restart of the same editor (caps lock may stay). */
    private boolean inputRestarting;
    /** While caps lock is on: the text before the cursor after the user's last edit, to recognise the user's field. */
    private String capsLockTail = "";
    private boolean suggestionsShowing;
    /** The last insertion's Inserted bar is still inside its {@link VoiceBar#INSERTED_MS} window. */
    private boolean insertedFresh;
    private String barMessage = "";
    private final Runnable barMessageExpiry = () -> {
        barMessage = "";
        renderBar();
    };
    private final Runnable insertedExpiry = this::expireInsertedBar;
    private long recordingStartedAt;
    /** The offline recorder's latest level, 0 to 1, for the waveform. */
    private volatile float offlineLevel;
    private final Runnable meterRunnable = this::updateMeter;
    // Counts input sessions: a new one each time focus moves to an editor, so two
    // fields with the same metadata are still told apart.
    private int editorSession;
    // Frozen when a recording starts: the app it may be delivered into and the
    // corrections that apply to it, whatever changes while it is processed.
    private DeliveryGuard.Target recordingTarget = new DeliveryGuard.Target("", "", -1);
    private List<PhraseReplacement> recordingCorrections = new ArrayList<>();
    // The vocabulary (prompt, keywords) frozen with them: the word lists may refresh mid-dictation.
    private WordLists.DictationWords recordingWords = WordLists.DictationWords.legacy(new ArrayList<>());
    /** 0.9.3: the speech model this dictation uses, fixed when its recording started (null: the app's). */
    private SpeechModels.Resolved recordingSpeech;
    /** 0.9.3: cancels the speech request in flight and its fallback; null when none is in flight. */
    private volatile SpeechCancel speechCancel;
    private final Object speechLock = new Object();
    /** 0.9.3: a dictation whose speech models all failed, kept (in memory) so it can be tried again. */
    private CloudCapture pendingRetry;
    private View workingCancelButton;
    private MediaRecorder recorder;
    private AudioRecord offlineRecorder;
    private Thread offlineRecordThread;
    private volatile boolean offlineRecordLoop;
    private File currentAudioFile;
    private File currentPcmFile;
    private EnglishEngine englishEngine;
    private Runnable deleteRepeatRunnable;
    private Runnable spaceCursorRunnable;
    private Runnable statusSpinnerRunnable;
    private ObjectAnimator translationLoadingAnimator;
    private ObjectAnimator createLoadingAnimator;
    private ObjectAnimator micLoadingAnimator;
    private ObjectAnimator instructionLoadingAnimator;
    private SeekBar expressionSlider;
    private Runnable correctionRunnable;
    private String statusSpinnerBase = "";
    private String translationTargetLanguage = "";
    private String instructionSourceText = "";
    private String pendingAutoCorrectWord = "";
    private String pendingAutoCorrectReplacement = "";
    private final List<String> pendingAutoCorrectSuggestions = new ArrayList<>();
    private String pendingAutoCompletePrefix = "";
    private final List<String> pendingAutoCompleteSuggestions = new ArrayList<>();
    private final Set<String> historyOriginalPreviewIds = new HashSet<>();
    private String lastAutoCorrectOriginal = "";
    private String lastAutoCorrectReplacement = "";
    // What an immediate backspace restores after a typed phrase replacement,
    // and the cursor position that backspace must come from.
    private String phraseUndoTyped = "";
    private String phraseUndoReplaced = "";
    private int phraseUndoCursor = -1;
    // Whether the current recording may be saved to history; set when it
    // starts and cleared by any private field it passes through.
    private boolean recordingMayRemember = true;
    private boolean pinyinCandidateStripExpanded;
    // The nine-key pad's side column (punctuation, or spellings while composing)
    // and its 重输 key; null whenever the pad is not on screen.
    private LinearLayout keypadSideColumn;
    private ScrollView keypadSideScroller;
    private String keypadSideSignature;
    private TextView keypadReenterKey;
    // The spelling of the 9-key reading, over the candidates ("ni'hao").
    private TextView pinyinSpellingText;
    // A configuration change (fold, unfold, rotate) rebuilds the input view and
    // restarts input on the same field; a composition survives it.
    private boolean rebuildingForConfiguration;
    private long lastConfigurationChangeMs = -1;
    private long lastHideRequestMs = -1;
    // 0.8.8: English is the main keyboard. Set when the keyboard closes or a new
    // field starts; applied before the keys are next shown (never mid-word).
    private boolean startInEnglish = true;
    private String languageFieldKey;
    // The second keyboard as last read from Settings, so a change made there is noticed.
    private String chineseLayoutSeen;
    // The list of keyboards the language key's hold opens, drawn inside the keyboard.
    private FrameLayout keyboardPicker;
    // The bar and the keys under the list, hidden from TalkBack while it is open.
    private View keyboardColumn;
    private int correctionGeneration;
    private int statusSpinnerStep;

    @Override
    public View onCreateInputView() {
        ui = Palette.from(this);
        colors = KeyColors.from(ui);
        // The framework calls this again after a configuration change, so this
        // is the one place geometry needs to be derived.
        metrics = KeyboardMetrics.from(getResources().getConfiguration(), Prefs.splitLargeScreens(this));
        if (!recording && !processing && !retoneMode) {
            // A rotation mid-recording keeps that message's style.
            String preset = Prefs.activePreset(this);
            choice.reset(preset, Prefs.expressionForPreset(this, preset));
        }
        if (!chineseAvailable() && inputMode.isChinese()) {
            // Chinese was switched off in Settings while the keyboard was away.
            inputMode = InputMode.ENGLISH;
            pinyinSession = null;
        }
        LinearLayout root = new SwipeRootLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(2), dp(4), dp(6));
        root.setBackgroundColor(ui.ime);

        // The bar and the keys, with the style sheet able to cover both.
        FrameLayout stack = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(buildStrip());
        keyboardPanel = buildKeyboardPanel();
        keyboardPanel.setOnTouchListener(this::handleSwipe);
        keyboardSurface = new FrameLayout(this);
        keyboardSurface.addView(keyboardPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        // 0.8.6: while a dictation records and is processed, the recording panel
        // (style chips and the expression slider) takes the keys' place, at their height.
        keyboardSurface.addView(buildRecordingPanel(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        column.addView(keyboardSurface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(metrics.keyHeightDp * 4)
        ));
        stack.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        voiceStyleOverlay = new LinearLayout(this);
        voiceStyleOverlay.setVisibility(View.GONE);
        stack.addView(voiceStyleOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        keyboardColumn = column;
        keyboardPicker = new FrameLayout(this);
        keyboardPicker.setVisibility(View.GONE);
        stack.addView(keyboardPicker, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(stack, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        refreshStyleChips();
        showIdleChips();
        updateAutoCapitalization();
        return root;
    }

    /**
     * Folding or unfolding the Fold (or turning any phone) is a configuration
     * change: the framework rebuilds the input view through
     * {@link #onCreateInputView()} and restarts input on the same field, all
     * inside this call. The flag lets that restart keep a half-typed pinyin run
     * instead of dropping it (0.8.7). A real field change afterwards still
     * settles it as before.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Always, not only mid-word (0.8.8): the rebuild must not count as a new
        // field, so the keyboard she was on stays too.
        rebuildingForConfiguration = true;
        lastConfigurationChangeMs = android.os.SystemClock.uptimeMillis();
        if (startInEnglish && lastHideRequestMs >= 0 && lastConfigurationChangeMs - lastHideRequestMs < 2_000) {
            // The window went away just before this fold or turn: that hide was the
            // fold's, not her closing the keyboard. A new field's request stays.
            startInEnglish = false;
            lastHideRequestMs = -1;
        }
        try {
            super.onConfigurationChanged(newConfig);
        } finally {
            rebuildingForConfiguration = false;
        }
    }

    @Override
    public void onDestroy() {
        watchClipboard(false);
        mainHandler.removeCallbacks(pasteExpiryRunnable);
        stopRecorderSilently();
        SpeechCancel inFlight = speechCancel;
        if (inFlight != null) {
            inFlight.cancel();
        }
        discardPendingRetry();
        if (pinyinEngine != null && pinyinEngine.rime() != null) {
            // Clears the breaker's marker: this was a clean stop, not a crash.
            pinyinEngine.rime().stop();
        }
        executor.shutdownNow();
        typingExecutor.shutdownNow();
        maintenanceExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputRestarting = restarting;
        // Every start is a new session, restarts included: a WebView moves between
        // its fields by restarting input on the same view.
        editorSession++;
        if (!restarting && emojiPicker != null) {
            populateKeyboardPanel(keyboardPanel);
        }
        activeInputType = attribute == null ? 0 : attribute.inputType;
        cancelRecordingIfKeyboardShouldReset();
        if (recording) {
            // A recording that survives a field change is kept only if every
            // field it passed through allows it.
            recordingMayRemember &= canRememberFromThisField();
        }
        if (rebuildingForConfiguration && restarting) {
            // Folding, unfolding or turning the phone restarts input on the same
            // field. What she was composing stays; the rebuilt strip shows it again.
        } else {
            settlePinyin(PinyinSession.SettleReason.EDITOR_GONE);
        }
        hideKeyboardPicker();
        if (!rebuildingForConfiguration) {
            // A new field starts in English. An app that restarts input on the
            // same field (after a send, say) keeps the keyboard she was on. The
            // field is tracked on restarts too, so a later fresh start of the
            // same field is not mistaken for a new one.
            String key = fieldKey(attribute);
            if (!restarting && !key.equals(languageFieldKey)) {
                startInEnglish = true;
            }
            languageFieldKey = key;
        }
        clearAutoCorrection();
        clearLastAutoCorrection();
        clearPhraseUndo();
        clearLastVoiceInsertion();
        if (!recording) {
            hideVoiceStyleOverlay();
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        restoreKeyboardForActiveInput();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // A fresh start in onStartInput is never turned into a restart here.
        inputRestarting = inputRestarting && restarting;
        activeInputType = info == null ? activeInputType : info.inputType;
        // Now that the field is known, start the 1.3 MB spelling lexicon parse
        // if this one can use suggestions. Waiting for the first keystroke
        // would put the parse in the worst possible place; onCreate is too
        // early, because there is no field yet and a password box never needs
        // it at all.
        if (shouldTypingAssistance()) {
            englishEngine().prepare();
        }
        // 0.9.0: pick up a Chinese engine changed in Settings while the keyboard was away.
        if (inputMode.isChinese() && pinyinSession != null && !pinyinSession.isComposing()) {
            pinyinEngine().prepare(inputMode);
            PinyinConverter converter = pinyinEngine().composerFor(inputMode);
            if (converter != null) {
                pinyinSession.setComposer(converter);
            }
        }
        watchClipboard(true);
        refreshPasteOffer(false);
        // At most once a day per model, and only online: an offline attempt
        // would use up the day's check.
        if (hasNetworkConnectivity()) {
            ModelAvailability.checkIfDue(this, maintenanceExecutor);
        }
        // The keyboard opening is the reliable sync trigger, at most every 15 minutes.
        android.content.Context app = getApplicationContext();
        maintenanceExecutor.execute(() -> {
            SyncScheduler.onKeyboardOpened(app);
            // The offline word lists, read ahead of the first dictation.
            WordLists.prewarm(app);
        });
        applyKeyboardSettingsChangedElsewhere();
        restoreKeyboardForActiveInput();
    }

    /**
     * Settings > Keyboard can change the split switch or the pinyin layout while
     * the keyboard is away; the cached view would keep the old ones until the
     * next fold or rotation. Picks them up when the keyboard next opens.
     */
    private void applyKeyboardSettingsChangedElsewhere() {
        if (keyboardPanel == null || recording || processing) {
            return;
        }
        // Only a change made in Settings moves a Chinese keyboard to the other
        // layout; cycling past the saved one must not be undone here (0.8.8).
        String layoutNow = Prefs.chineseLayout(this);
        boolean layoutChanged = chineseLayoutSeen != null && !layoutNow.equals(chineseLayoutSeen);
        chineseLayoutSeen = layoutNow;
        if (layoutChanged && inputMode.isChinese() && chineseAvailable() && !composingPinyin()) {
            InputMode wanted = InputMode.chineseFor(layoutNow);
            if (wanted != inputMode) {
                setInputMode(wanted);
            }
        }
        boolean splitWanted = KeyboardGeometry.shouldSplit(
                getResources().getConfiguration().smallestScreenWidthDp, Prefs.splitLargeScreens(this));
        if (splitWanted != metrics.split) {
            setInputView(onCreateInputView());
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        watchClipboard(false);
        if (!finishingInput && !rebuildingForConfiguration && composingPinyin()) {
            // The keyboard is closing on the same field, and the framework is about
            // to end the composing span. Settle the word now (0.8.8), so the buffer
            // and the field never disagree when it reopens. Leaving the field drops
            // it instead (onFinishInput); a fold keeps it.
            settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
        }
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        // The keyboard appearing is a check for updates too, at most every 15 minutes.
        android.content.Context app = getApplicationContext();
        maintenanceExecutor.execute(() -> SyncScheduler.onKeyboardOpened(app));
        refreshPasteOffer(false);
        restoreKeyboardForActiveInput();
    }

    private void restoreKeyboardForActiveInput() {
        if (emojiPicker != null && !recording && !processing) {
            return;
        }
        if (recording) {
            showCaptureRecordingState();
        } else if (!processing && keyboardPanel != null) {
            applyStartInEnglish();
            // Some confirmation-code editors restart or advance the active
            // field after every digit, so the same field keeps the layer on
            // screen. A different field, or the same one changing type, starts
            // from its own type. Nothing is recorded before the view exists, so
            // the first field after a cold start is decided once it does.
            String fieldKey = fieldKey(getCurrentInputEditorInfo());
            boolean sameField = fieldKey.equals(layerFieldKey);
            layerFieldKey = fieldKey;
            KeyboardLayerRules.Layer layer = KeyboardLayerRules.layerFor(activeInputType, sameField, currentLayer());
            // Some apps restart their field after every change; caps lock stays on
            // for the same field, as it did before the restart.
            // Only a restart the system calls a restart, and only when the text before
            // the cursor is what the user just typed there: two fields of one WebView can share
            // package, id and type and restart between each other (Codex review).
            boolean keepCapsLock = sameField && inputRestarting && capsLock
                    && layer == KeyboardLayerRules.Layer.LETTERS
                    && !capsLockTail.isEmpty() && capsLockTail.equals(textBeforeCursorTail());
            resetToRegularKeyboard(layer);
            if (keepCapsLock) {
                capsLock = true;
                shift = true;
                autoShift = false;
                updateShiftVisuals();
            }
        }
    }

    /** Identifies a field well enough to tell it restarting from a new one. */
    private static String fieldKey(EditorInfo info) {
        return info == null ? "" : info.packageName + ":" + info.fieldId + ":" + info.inputType;
    }

    private KeyboardLayerRules.Layer currentLayer() {
        if (!symbolsMode) {
            return KeyboardLayerRules.Layer.LETTERS;
        }
        switch (symbolPage) {
            case MORE:
                return KeyboardLayerRules.Layer.MORE_SYMBOLS;
            case NUMPAD:
                return KeyboardLayerRules.Layer.NUMPAD;
            default:
                return KeyboardLayerRules.Layer.SYMBOLS;
        }
    }

    private void setLayerState(KeyboardLayerRules.Layer layer) {
        symbolsMode = layer != KeyboardLayerRules.Layer.LETTERS;
        symbolPage = layer == KeyboardLayerRules.Layer.MORE_SYMBOLS ? SymbolPage.MORE
                : layer == KeyboardLayerRules.Layer.NUMPAD ? SymbolPage.NUMPAD
                : SymbolPage.PRIMARY;
    }

    private String layerStatus(KeyboardLayerRules.Layer layer) {
        switch (layer) {
            case SYMBOLS:
                return "Symbols";
            case MORE_SYMBOLS:
                return "More symbols";
            case NUMPAD:
                return "Numbers";
            default:
                return capsLock ? "Caps lock" : "Ready";
        }
    }

    /** Switches layer by hand: the number pad's ABC key or the symbols layer's 1234 key. */
    private void showLayer(KeyboardLayerRules.Layer layer) {
        if (recording || processing || keyboardPanel == null) {
            return;
        }
        settlePinyin(PinyinSession.SettleReason.MODE_CHANGED);
        setLayerState(layer);
        shift = false;
        autoShift = false;
        lastShiftTapMs = 0;
        populateKeyboardPanel(keyboardPanel);
        if (!symbolsMode) {
            updateAutoCapitalization();
        }
        setStatus(layerStatus(layer));
    }

    @Override
    public void onFinishInput() {
        cancelRecordingIfKeyboardShouldReset();
        // The editor is going away: drop the buffer without writing anything.
        // Committing here risks landing text in whatever field comes next.
        settlePinyin(PinyinSession.SettleReason.EDITOR_GONE);
        clearAutoCorrection();
        clearLastAutoCorrection();
        clearPhraseUndo();
        clearLastVoiceInsertion();
        if (emojiPicker != null) populateKeyboardPanel(keyboardPanel);
        super.onFinishInput();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboardPickerOpen()) {
            return true;
        }
        if (emojiPicker != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) return true;
            if (emojiPicker.handleHardwareKey(keyCode, event)) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboardPickerOpen()) {
            // Back closes the list first, as it does the emoji keyboard.
            hideKeyboardPicker();
            return true;
        }
        if (emojiPicker != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) return emojiPicker.handleBack();
            if (emojiPicker.isSearching() && !event.isSystem()) return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onWindowHidden() {
        cancelRecordingIfKeyboardShouldReset();
        hideKeyboardPicker();
        // The keyboard opens in English next time (0.8.8), unless the window only
        // went away for a fold, unfold or turn. Recorded while a dictation is busy
        // too; it is applied when that dictation ends.
        if (!configurationJustChanged()) {
            startInEnglish = true;
            lastHideRequestMs = android.os.SystemClock.uptimeMillis();
        }
        if (!recording && !processing) {
            resetToRegularKeyboard();
        }
        layerFieldKey = null;
        if (!recording && !processing && !retoneMode) {
            hideVoiceStyleOverlay();
            resetChoiceToDefault();
            barMessage = "";
            mainHandler.removeCallbacks(barMessageExpiry);
        }
        if (modelNoticeShown) {
            modelNotice = "";
            modelNoticeShown = false;
        }
        watchClipboard(false);
        mainHandler.removeCallbacks(pasteExpiryRunnable);
        super.onWindowHidden();
    }

    @Override
    public void onUpdateSelection(
            int oldSelStart,
            int oldSelEnd,
            int newSelStart,
            int newSelEnd,
            int candidatesStart,
            int candidatesEnd
    ) {
        super.onUpdateSelection(
                oldSelStart,
                oldSelEnd,
                newSelStart,
                newSelEnd,
                candidatesStart,
                candidatesEnd
        );
        // The cursor left the composing span, so the buffer no longer describes
        // where the text would land. Leave what is on screen and stop composing.
        //
        // Deliberately conservative: a missing composing region (candidatesStart
        // < 0, which some editors and WebViews always report) is NOT treated as
        // having moved away. Doing so would settle on our own setComposingText
        // callback and kill the composition on every single keystroke. A stale
        // buffer in such an editor is cleaned up by the other settle points.
        if (composingPinyin()
                && candidatesStart >= 0
                && (newSelStart < candidatesStart || newSelEnd > candidatesEnd)) {
            settlePinyin(PinyinSession.SettleReason.CURSOR_MOVED);
        }
        if (!lastVoiceRawTranscript.isEmpty()
                && lastVoiceSelectionEnd >= 0
                && (newSelStart != lastVoiceSelectionEnd || newSelEnd != lastVoiceSelectionEnd)) {
            clearLastVoiceInsertion();
        }
        if (phraseUndoCursor >= 0
                && (newSelStart != phraseUndoCursor || newSelEnd != phraseUndoCursor)) {
            clearPhraseUndo();
        }
        if (capsLock) {
            capsLockTail = textBeforeCursorTail();
        }
    }

    /** The last few characters before the cursor, or "" when the editor cannot say. */
    private String textBeforeCursorTail() {
        InputConnection connection = getCurrentInputConnection();
        CharSequence before = connection == null ? null : connection.getTextBeforeCursor(32, 0);
        return before == null ? "" : before.toString();
    }

    private boolean handleSwipe(View view, MotionEvent event) {
        return false;
    }

    private void cancelRecordingIfKeyboardShouldReset() {
        if (recording && !processing && Prefs.cancelRecordingWhenHidden(this)) {
            cancelRecording();
        }
    }

    private void resetToRegularKeyboard() {
        resetToRegularKeyboard(KeyboardLayerRules.Layer.LETTERS);
    }

    private void resetToRegularKeyboard(KeyboardLayerRules.Layer layer) {
        if (keyboardPanel == null || recording || processing) {
            return;
        }
        retoneMode = false;
        historyMode = false;
        setLayerState(layer);
        shift = false;
        capsLock = false;
        lastShiftTapMs = 0;
        hideVoiceStyleOverlay();
        setKeyboardLocked(false);
        setRetoneTopControls(false);
        setHistoryControlsActive(false);
        populateKeyboardPanel(keyboardPanel);
        hideChipStrip();
        showIdleChips();
        updateAutoCapitalization();
        setStatus(layerStatus(layer));
    }

    private boolean isHorizontalSwipe(float deltaX, float deltaY) {
        return Math.abs(deltaX) > dp(18) && Math.abs(deltaX) > Math.abs(deltaY) * 1.18f;
    }

    private boolean handleRootSwipe(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            rootDownX = event.getRawX();
            rootDownY = event.getRawY();
            rootSwipeConsumed = false;
            rootSwipeTracking = false;
            rootSwipeStartedOnSpace = isRawPointInsideAny(spaceButtons, rootDownX, rootDownY);
            rootSwipeStartedOnExpressionSlider = isRawPointInside(expressionSlider, rootDownX, rootDownY)
                    || isRawPointInside(panelSlider, rootDownX, rootDownY);
            rootSwipeStartedOnVoiceStyleScroller = isRawPointInside(
                    voiceStyleScroller,
                    rootDownX,
                    rootDownY
            );
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            animatePanelReset();
            rootSwipeConsumed = false;
            rootSwipeTracking = false;
            return false;
        }
        if (rootSwipeConsumed) {
            if (action == MotionEvent.ACTION_UP) {
                rootSwipeConsumed = false;
                rootSwipeTracking = false;
            }
            return true;
        }
        if (rootSwipeStartedOnSpace
                || rootSwipeStartedOnExpressionSlider
                || rootSwipeStartedOnVoiceStyleScroller
                || (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP)) {
            return false;
        }
        float deltaX = event.getRawX() - rootDownX;
        float deltaY = event.getRawY() - rootDownY;
        if (!rootSwipeTracking && (!canStartRootSwipe(deltaX, deltaY) || !canUseRootSwipe(deltaX))) {
            return false;
        }
        rootSwipeTracking = true;
        if (action == MotionEvent.ACTION_MOVE) {
            updateSwipePreview(deltaX);
            return true;
        }

        rootSwipeConsumed = true;
        rootSwipeTracking = false;
        boolean committed = Math.abs(deltaX) >= swipeCommitDistance();
        if (committed && canUseRootSwipe(deltaX)) {
            if (recording) {
                animatePanelReset();
                cyclePreset(deltaX < 0 ? 1 : -1);
                return true;
            }
            if (historyMode && deltaX > 0) {
                hideHistoryPanelAnimated();
                return true;
            }
        }
        animatePanelReset();
        return true;
    }

    private boolean canUseRootSwipe(float deltaX) {
        if (recording) {
            return !translationCapture && !creationCapture && !instructionCapture;
        }
        if (processing || panelAnimating) {
            return false;
        }
        return historyMode && deltaX > 0;
    }

    private boolean canStartRootSwipe(float deltaX, float deltaY) {
        if (!historyMode && !recording) {
            return false;
        }
        return isHorizontalSwipe(deltaX, deltaY);
    }

    private int swipeCommitDistance() {
        int panelWidth = keyboardPanel == null ? 0 : keyboardPanel.getWidth();
        if (!historyMode && !recording) {
            return Math.max(dp(96), panelWidth / 4);
        }
        return Math.max(dp(46), panelWidth / 7);
    }

    private void updateSwipePreview(float deltaX) {
        if (keyboardPanel == null || recording) {
            return;
        }
        int width = Math.max(keyboardPanel.getWidth(), dp(320));
        float max = width * 0.28f;
        float constrained = Math.max(-max, Math.min(max, deltaX));
        keyboardPanel.animate().cancel();
        keyboardPanel.setTranslationX(constrained * 0.45f);
        keyboardPanel.setAlpha(1f - Math.min(0.24f, Math.abs(constrained) / width));
    }

    private void animatePanelReset() {
        if (keyboardPanel == null || panelAnimating) {
            return;
        }
        keyboardPanel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(110)
                .start();
    }

    private boolean isRawPointInside(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private boolean isRawPointInsideAny(List<? extends View> views, float rawX, float rawY) {
        for (View view : views) {
            if (isRawPointInside(view, rawX, rawY)) {
                return true;
            }
        }
        return false;
    }

    private LinearLayout buildStrip() {
        // The input view can be rebuilt while the service and pinyin session
        // survive a configuration change. The new toolbar starts uncollapsed;
        // showPinyinCandidates() will expand it again when needed.
        pinyinCandidateStripExpanded = false;
        compactBar = getResources().getConfiguration().screenWidthDp < VoiceBar.COMPACT_WIDTH_DP;
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        barHost = new FrameLayout(this);
        outer.addView(barHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(BAR_HEIGHT_DP)
        ));

        // The words for "Transcribing", "Tidying up" and the results; the working
        // bar shows it, and the idle bar borrows it for a short message.
        statusText = barText("Ready", 14, false, ui.fg);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        // ---------------------------------------------------------------- idle
        idleBar = barRow();
        FrameLayout leftSlot = new FrameLayout(this);
        styleChip = styleChip();
        styleChip.setOnClickListener(v -> {
            haptic(v);
            toggleStyleSheet();
        });
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
                Gravity.START | Gravity.CENTER_VERTICAL
        );
        leftSlot.addView(styleChip, chipParams);
        messageText = barText("", 13, false, ui.muted);
        messageText.setMaxLines(2);
        messageText.setEllipsize(TextUtils.TruncateAt.END);
        messageText.setGravity(Gravity.CENTER_VERTICAL);
        messageText.setPadding(dp(6), 0, dp(6), 0);
        messageText.setVisibility(View.GONE);
        messageText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        leftSlot.addView(messageText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        idleBar.addView(leftSlot, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        dictateButton = tonalPill(compactBar ? "" : "Dictate", R.drawable.ph_microphone, 44);
        dictateButton.setContentDescription("Dictate");
        dictateButton.setOnClickListener(v -> {
            haptic(v);
            toggleVoiceCapture();
        });
        LinearLayout.LayoutParams dictateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
        );
        dictateParams.setMargins(dp(6), 0, dp(2), 0);
        idleBar.addView(dictateButton, dictateParams);

        topHistoryButton = iconButton(R.drawable.ph_clock_counter_clockwise, "History", v -> {
            if (processing) {
                return;
            }
            haptic(v);
            if (historyMode) {
                hideHistoryPanelAnimated();
            } else {
                showHistoryPanelAnimated();
            }
        });
        idleBar.addView(topHistoryButton);
        overflowButton = iconButton(R.drawable.ph_dots_three,
                "More: translate, create, voice edit, spelling and settings", v -> {
                    haptic(v);
                    showOverflowMenu(v);
                });
        idleBar.addView(overflowButton);
        barHost.addView(idleBar, barParams());

        // --------------------------------------------------------- suggestions
        suggestionBar = barRow();
        chipStrip = new LinearLayout(this);
        chipStrip.setOrientation(LinearLayout.HORIZONTAL);
        chipStrip.setGravity(Gravity.CENTER_VERTICAL);
        chipScroller = new HorizontalScrollView(this);
        chipScroller.setHorizontalScrollBarEnabled(false);
        chipScroller.setFillViewport(true);
        chipScroller.addView(chipStrip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        // 0.8.7: on the nine-key pad the reading of the digits ("ni'hao") sits in
        // a small line over the candidates, inside the bar's own height.
        LinearLayout candidateColumn = new LinearLayout(this);
        candidateColumn.setOrientation(LinearLayout.VERTICAL);
        pinyinSpellingText = barText("", 12, false, ui.muted);
        pinyinSpellingText.setSingleLine(true);
        pinyinSpellingText.setEllipsize(TextUtils.TruncateAt.START);
        pinyinSpellingText.setPadding(dp(8), 0, dp(8), 0);
        pinyinSpellingText.setIncludeFontPadding(false);
        pinyinSpellingText.setVisibility(View.GONE);
        pinyinSpellingText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        candidateColumn.addView(pinyinSpellingText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(PINYIN_SPELLING_LINE_DP)));
        candidateColumn.addView(chipScroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        suggestionBar.addView(candidateColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        micButton = micButton();
        micButton.setOnClickListener(v -> {
            haptic(v);
            toggleVoiceCapture();
        });
        suggestionBar.addView(micButton);
        barHost.addView(suggestionBar, barParams());

        // ----------------------------------------------------------- listening
        listeningBar = barRow();
        recordDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ui.record);
        dot.setStroke(dp(4), Palette.withAlpha(ui.record, 46));
        recordDot.setBackground(dot);
        recordDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        dotParams.setMargins(dp(2), 0, dp(4), 0);
        listeningBar.addView(recordDot, dotParams);
        listeningLabel = barText("Listening", 13, true, ui.fg);
        listeningLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        listeningBar.addView(listeningLabel);
        waveform = new WaveformView(this, ui.wave);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(0, dp(30), 1f);
        waveParams.setMargins(dp(8), 0, dp(8), 0);
        listeningBar.addView(waveform, waveParams);
        timerText = barText("0:00", 13, false, ui.muted);
        timerText.setFontFeatureSettings("tnum");
        timerText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        listeningBar.addView(timerText);
        cancelRecordingButton = iconButton(R.drawable.ph_x, "Cancel recording", v -> {
            haptic(v);
            if (retoneMode) {
                closeRetoneOverlay("Retone canceled");
            } else {
                cancelRecording();
            }
        });
        listeningBar.addView(cancelRecordingButton);
        stopButton = tonalPill(compactBar ? "" : "Stop", R.drawable.ph_stop_fill, 40);
        stopButton.setContentDescription("Stop and insert");
        stopButton.setOnClickListener(v -> {
            haptic(v);
            stopCurrentCapture();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
        );
        stopParams.setMargins(dp(2), 0, 0, 0);
        listeningBar.addView(stopButton, stopParams);
        barHost.addView(listeningBar, barParams());

        // ------------------------------------------------------------- working
        workingBar = barRow();
        workingIcon = new android.widget.ImageView(this);
        workingIcon.setImageResource(R.drawable.ph_circle_notch);
        workingIcon.setColorFilter(ui.accent);
        workingIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams workingIconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        workingIconParams.setMargins(dp(6), 0, dp(10), 0);
        workingBar.addView(workingIcon, workingIconParams);
        workingBar.addView(statusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        workingCancelButton = iconButton(R.drawable.ph_x, "Cancel transcription", v -> {
            haptic(v);
            cancelSpeechRequest();
        });
        workingCancelButton.setVisibility(View.GONE);
        workingBar.addView(workingCancelButton);
        barHost.addView(workingBar, barParams());

        // ------------------------------------------- inserted and kept-words
        doneBar = barRow();
        doneIcon = new android.widget.ImageView(this);
        doneIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams doneIconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        doneIconParams.setMargins(dp(4), 0, dp(8), 0);
        doneBar.addView(doneIcon, doneIconParams);
        doneLabels = new LinearLayout(this);
        doneLabels.setOrientation(LinearLayout.VERTICAL);
        doneLabels.setGravity(Gravity.CENTER_VERTICAL);
        doneLabels.setMinimumHeight(dp(48));
        doneTitle = barText("Inserted", 13, true, ui.fg);
        doneTitle.setSingleLine(true);
        doneTitle.setEllipsize(TextUtils.TruncateAt.END);
        doneSubtitle = barText("", 11, false, ui.subtle);
        doneSubtitle.setSingleLine(true);
        doneSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        doneSubtitle.setPadding(0, dp(2), 0, 0);
        doneLabels.addView(doneTitle);
        doneLabels.addView(doneSubtitle);
        doneLabels.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        doneBar.addView(doneLabels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        doneActions = new LinearLayout(this);
        doneActions.setOrientation(LinearLayout.HORIZONTAL);
        doneActions.setGravity(Gravity.CENTER_VERTICAL);
        doneBar.addView(doneActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        barHost.addView(doneBar, barParams());

        // ------------------------------------------ history and retone header
        headerBar = barRow();
        headerTitle = barText("", 15, true, ui.fg);
        headerTitle.setPadding(dp(8), 0, dp(8), 0);
        headerBar.addView(headerTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerAction = outlinePill("", 0);
        LinearLayout.LayoutParams headerActionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
        );
        headerBar.addView(headerAction, headerActionParams);
        barHost.addView(headerBar, barParams());

        renderBar();
        return outer;
    }

    private FrameLayout.LayoutParams barParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout barRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(2), 0);
        row.setVisibility(View.GONE);
        return row;
    }

    private TextView barText(String value, float sp, boolean strong, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(strong ? Fonts.semibold(this) : Fonts.regular(this));
        text.setIncludeFontPadding(false);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    /** A 48 dp round icon button with a quiet ripple. */
    private ImageButton iconButton(int drawableRes, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setColorFilter(ui.muted);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.withAlpha(ui.fg, 36)), null, mask));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    /** The one tonal primary: accent tint, its edge and accent ink. Drawn {@code visibleDp} tall in a 48 dp target. */
    private TextView tonalPill(String text, int iconRes, int visibleDp) {
        TextView pill = pillBase(text, iconRes, ui.accentInk, 14, Fonts.semibold(this));
        Ui.setBackgroundKeepPadding(pill, pillBackground(ui.accentTint, ui.accentTintBorder, visibleDp, 999));
        pill.setPadding(dp(text.isEmpty() ? 13 : 14), 0, dp(text.isEmpty() ? 13 : 17), 0);
        return pill;
    }

    /** A secondary chip: surface, a hairline edge and plain text. */
    private TextView outlinePill(String text, int iconRes) {
        TextView pill = pillBase(text, iconRes, ui.fg, 13, Fonts.medium(this));
        Ui.setBackgroundKeepPadding(pill, pillBackground(ui.surface, ui.borderStrong, 34, 999));
        pill.setPadding(dp(iconRes == 0 ? 13 : 10), 0, dp(13), 0);
        return pill;
    }

    private TextView pillBase(String text, int iconRes, int color, float sp, Typeface face) {
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextSize(sp);
        pill.setTextColor(color);
        pill.setTypeface(face);
        pill.setSingleLine(true);
        pill.setIncludeFontPadding(false);
        pill.setGravity(Gravity.CENTER);
        pill.setMinHeight(dp(48));
        pill.setMinWidth(dp(48));
        pill.setClickable(true);
        pill.setFocusable(true);
        if (iconRes != 0) {
            Drawable icon = getDrawable(iconRes);
            if (icon != null) {
                icon = icon.mutate();
                icon.setBounds(0, 0, dp(18), dp(18));
                icon.setTint(color);
                pill.setCompoundDrawables(icon, null, null, null);
                pill.setCompoundDrawablePadding(text.isEmpty() ? 0 : dp(6));
            }
        }
        return pill;
    }

    /** A rounded fill and edge, inset so a 48 dp target draws {@code visibleDp} tall. */
    private Drawable pillBackground(int fill, int edge, int visibleDp, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(Math.min(radiusDp, visibleDp / 2 + 1)));
        if (edge != 0) {
            shape.setStroke(Math.max(1, dp(1)), edge);
        }
        int inset = Math.max(0, (48 - visibleDp) / 2);
        return new InsetDrawable(shape, 0, dp(inset), 0, dp(inset));
    }

    /** "✦ Clean · Natural ⌄": the style for the next message, one tap from the sheet. */
    private TextView styleChip() {
        TextView chip = new TextView(this);
        chip.setTextSize(13);
        chip.setTypeface(Fonts.medium(this));
        chip.setTextColor(ui.muted);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setIncludeFontPadding(false);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinHeight(dp(48));
        chip.setClickable(true);
        chip.setFocusable(true);
        Ui.setBackgroundKeepPadding(chip, pillBackground(ui.surface, ui.borderStrong, 34, 999));
        chip.setPadding(dp(10), 0, dp(10), 0);
        Drawable sparkle = getDrawable(R.drawable.ph_sparkle);
        if (sparkle != null) {
            sparkle = sparkle.mutate();
            sparkle.setBounds(0, 0, dp(16), dp(16));
            sparkle.setTint(ui.accent);
        }
        Drawable caret = getDrawable(R.drawable.ph_caret_down);
        if (caret != null) {
            caret = caret.mutate();
            caret.setBounds(0, 0, dp(14), dp(14));
            caret.setTint(ui.subtle);
        }
        chip.setCompoundDrawables(sparkle, null, caret, null);
        chip.setCompoundDrawablePadding(dp(6));
        return chip;
    }

    /** Bold style name, then "· expression", as on desktop. */
    private CharSequence styleChipText(String preset, int expression) {
        String name = labelForPreset(preset);
        String text = name + "  ·  " + Prefs.expressionLabel(expression);
        android.text.SpannableString span = new android.text.SpannableString(text);
        span.setSpan(new android.text.style.ForegroundColorSpan(ui.fg), 0, name.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Object strong = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? new android.text.style.TypefaceSpan(Fonts.semibold(this))
                : new android.text.style.StyleSpan(Typeface.BOLD);
        span.setSpan(strong, 0, name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private void refreshStyleChips() {
        if (ui == null) {
            return;
        }
        CharSequence text = styleChipText(choice.preset, choice.expression);
        String description = "Style: " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression)
                + ". Change for this message";
        if (styleChip != null) {
            styleChip.setText(text);
            styleChip.setContentDescription(description);
        }
    }

    private LinearLayout buildKeyboardPanel() {
        keyButtons.clear();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(metrics.outerInsetDp), 0, dp(metrics.outerInsetDp), 0);
        panel.setOnTouchListener(this::handleSwipe);
        populateKeyboardPanel(panel);
        return panel;
    }

    private void populateKeyboardPanel(LinearLayout panel) {
        dismissEmojiPicker();
        historyMode = false;
        panel.removeAllViews();
        keyButtons.clear();
        letterButtons.clear();
        shiftButton = null;
        spaceButtons.clear();
        keypadSideColumn = null;
        keypadSideScroller = null;
        keypadReenterKey = null;
        if (symbolsMode) {
            if (symbolPage == SymbolPage.NUMPAD) {
                addNumpadRows(panel);
                return;
            }
            boolean more = symbolPage == SymbolPage.MORE;
            if (more) {
                panel.addView(keyRow(KeyboardSymbols.alternateTopRow()));
                panel.addView(keyRow(KeyboardSymbols.alternateMiddleRow()));
            } else {
                panel.addView(keyRow(KeyboardSymbols.primaryTopRow()));
                panel.addView(keyRow(KeyboardSymbols.primaryMiddleRow()));
            }
            LinearLayout third = new LinearLayout(this);
            third.setOrientation(LinearLayout.HORIZONTAL);
            third.addView(keyButton(more ? "123" : "#+=", 1.2f, v -> toggleMoreSymbolsMode(), true));
            String[] thirdRow = more
                    ? KeyboardSymbols.alternateBottomRow()
                    : KeyboardSymbols.primaryBottomRow();
            int symbolSplit = splitIndexFor(KeyboardGeometry.Row.SYMBOLS_THIRD, thirdRow.length);
            for (int i = 0; i < thirdRow.length; i++) {
                if (i == symbolSplit) {
                    third.addView(splitSpacer());
                }
                third.addView(keyButton(thirdRow[i], 1f, v -> commitKey(((TextView) v).getText().toString())));
            }
            third.addView(deleteKey());
            panel.addView(third);
            panel.addView(bottomRow("ABC"));
            return;
        }

        if (inputMode.isKeypad()) {
            // The nine-key pad brings its own bottom row (符 123 ☺ space 中/英).
            addKeypadRows(panel);
            return;
        }

        panel.addView(keyRow("qwertyuiop"));
        panel.addView(letterMiddleRow());

        LinearLayout third = new LinearLayout(this);
        third.setOrientation(LinearLayout.HORIZONTAL);
        shiftButton = shiftKey();
        third.addView(shiftButton);
        String bottomLetters = "zxcvbnm";
        int bottomSplit = splitIndexFor(KeyboardGeometry.Row.BOTTOM_LETTERS, bottomLetters.length());
        for (int i = 0; i < bottomLetters.length(); i++) {
            if (i == bottomSplit) {
                third.addView(splitSpacer());
            }
            third.addView(letterKeyButton(String.valueOf(bottomLetters.charAt(i)), 1f));
        }
        third.addView(deleteKey());
        panel.addView(third);
        panel.addView(bottomRow("?123"));
        updateShiftVisuals();
    }

    private void showHistoryPanel() {
        if (keyboardPanel == null || recording || processing || !canShowHistory()) {
            return;
        }
        dismissEmojiPicker();
        historyMode = true;
        symbolsMode = false;
        symbolPage = SymbolPage.PRIMARY;
        shift = false;
        autoShift = false;
        lastShiftTapMs = 0;
        clearAutoCorrection();
        hideChipStrip();
        setHistoryControlsActive(true);
        setStatus("History");
        keyboardPanel.removeAllViews();
        keyButtons.clear();
        letterButtons.clear();
        shiftButton = null;
        spaceButtons.clear();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOnTouchListener(this::handleSwipe);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(metrics.keyHeightDp * 4)
        ));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(4));
        scroll.addView(content);

        // Always reachable here, however long ago the text was copied.
        if (clipboardHasText()) {
            LinearLayout pasteRow = new LinearLayout(this);
            pasteRow.setOrientation(LinearLayout.HORIZONTAL);
            pasteRow.setPadding(0, dp(4), 0, dp(4));
            TextView paste = historyActionButton("Paste clipboard", v -> pasteClipboard(), true);
            paste.setContentDescription("Paste clipboard");
            pasteRow.addView(paste);
            content.addView(pasteRow);
        }

        List<VoiceHistoryItem> history = Prefs.transcriptHistory(this);
        if (history.isEmpty()) {
            TextView empty = historyText("No transcripts yet.", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(keyVisualBackground(colors.key, false));
            content.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(metrics.keyHeightDp * 4)
            ));
        } else {
            for (VoiceHistoryItem item : history) {
                content.addView(historyItemView(item));
            }
        }
        keyboardPanel.addView(scroll);
    }

    private void hideHistoryPanel() {
        if (keyboardPanel == null) {
            return;
        }
        historyMode = false;
        setHistoryControlsActive(false);
        hideChipStrip();
        populateKeyboardPanel(keyboardPanel);
        setStatus(capsLock ? "Caps lock" : "Ready");
        updateAutoCapitalization();
    }

    private void showHistoryPanelAnimated() {
        animateHistoryTransition(true);
    }

    private void hideHistoryPanelAnimated() {
        animateHistoryTransition(false);
    }

    private void animateHistoryTransition(boolean toHistory) {
        if (keyboardPanel == null || panelAnimating || processing) {
            return;
        }
        if (toHistory && (historyMode || recording || !canShowHistory())) {
            return;
        }
        if (!toHistory && !historyMode) {
            return;
        }
        panelAnimating = true;
        keyboardPanel.animate().cancel();
        int width = Math.max(keyboardPanel.getWidth(), dp(320));
        float outX = toHistory ? -width * 0.34f : width * 0.34f;
        float inX = toHistory ? width * 0.25f : -width * 0.25f;
        keyboardPanel.animate()
                .translationX(outX)
                .alpha(0.24f)
                .setDuration(90)
                .withEndAction(() -> {
                    if (toHistory) {
                        showHistoryPanel();
                    } else {
                        hideHistoryPanel();
                    }
                    keyboardPanel.setTranslationX(inX);
                    keyboardPanel.setAlpha(0.36f);
                    keyboardPanel.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(145)
                            .withEndAction(() -> {
                                keyboardPanel.setTranslationX(0f);
                                keyboardPanel.setAlpha(1f);
                                panelAnimating = false;
                            })
                            .start();
                })
                .start();
    }

    private View historyItemView(VoiceHistoryItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(12), dp(8));
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setColor(ui.surface);
        cardShape.setCornerRadius(dp(14));
        cardShape.setStroke(Math.max(1, dp(1)), ui.border);
        card.setBackground(cardShape);
        card.setOnTouchListener(this::handleSwipe);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(dp(2), dp(2), dp(2), dp(6));
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView meta = historyText(historyMeta(item), 12, false);
        meta.setTextColor(ui.subtle);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(meta, new LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
        ));
        header.addView(historyOpenButton(item));
        TextView outputMenu = historyOutputButton(item);
        header.addView(outputMenu);
        card.addView(header);

        boolean hasSelectedOutput = item.hasOutputForVariant(item.preset, item.expression);
        boolean transformedPreset = !Prefs.PRESET_RAW.equals(item.preset);
        boolean showingOriginal = transformedPreset
                && (!hasSelectedOutput || historyOriginalPreviewIds.contains(item.id));

        TextView previewMode = historyText(historyPreviewModeText(item, hasSelectedOutput, showingOriginal), 11.5f, true);
        previewMode.setTextColor(ui.subtle);
        previewMode.setPadding(0, dp(4), 0, 0);
        card.addView(previewMode);

        TextView preview = historyText(compactPreview(historyVisibleText(item)), 14.5f, false);
        preview.setLineSpacing(0, 1.25f);
        preview.setMaxLines(4);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setPadding(0, dp(4), 0, dp(10));
        card.addView(preview);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout leftActions = new LinearLayout(this);
        leftActions.setOrientation(LinearLayout.HORIZONTAL);
        if (hasSelectedOutput) {
            leftActions.addView(historyActionButton("Copy", v -> copyHistoryText(historyVisibleText(item)), false));
            leftActions.addView(historyActionButton("Paste", v -> pasteHistoryText(historyVisibleText(item)), true));
        } else {
            leftActions.addView(historyActionButton(
                    "Create " + historyOutputLabel(item.preset, item.expression),
                    v -> createHistoryTransform(item, item.preset, item.expression),
                    true
            ));
        }
        actions.addView(leftActions);
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1f));
        if (transformedPreset) {
            actions.addView(historyOriginalToggle(item, preview, previewMode, hasSelectedOutput));
        }
        card.addView(actions);
        return card;
    }

    private String historyVisibleText(VoiceHistoryItem item) {
        boolean hasSelectedOutput = item.hasOutputForVariant(item.preset, item.expression);
        boolean showOriginal = !hasSelectedOutput || historyOriginalPreviewIds.contains(item.id);
        return showOriginal ? item.rawText : item.outputForVariant(item.preset, item.expression);
    }

    private String historyPreviewModeText(VoiceHistoryItem item, boolean hasSelectedOutput, boolean showingOriginal) {
        String selectedVersion = historyOutputLabel(item.preset, item.expression);
        if (item.isTranslation() && !item.targetLanguage.isEmpty()) {
            selectedVersion = compactLanguageName(item.targetLanguage) + " · " + selectedVersion;
        }
        if (!hasSelectedOutput && !Prefs.PRESET_RAW.equals(item.preset)) {
            return "Your original words · " + selectedVersion + " not made yet";
        }
        if (showingOriginal || Prefs.PRESET_RAW.equals(item.preset)) {
            return item.isTranslation() ? "Original source" : item.isCreation() ? "What you asked for" : "Your original words";
        }
        return selectedVersion;
    }

    private Switch historyOriginalToggle(
            VoiceHistoryItem item,
            TextView preview,
            TextView previewMode,
            boolean hasSelectedOutput
    ) {
        Switch toggle = new Switch(this);
        toggle.setText("Show original");
        toggle.setTextSize(12);
        toggle.setTextColor(ui.muted);
        toggle.setTypeface(Fonts.medium(this));
        toggle.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accent, ui.dark ? ui.muted : ui.surface}));
        toggle.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accentTintBorder, ui.borderStrong}));
        toggle.setIncludeFontPadding(false);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(6), 0, 0, 0);
        toggle.setMinWidth(0);
        toggle.setMinHeight(0);
        toggle.setChecked(!hasSelectedOutput || historyOriginalPreviewIds.contains(item.id));
        toggle.setEnabled(hasSelectedOutput);
        toggle.setAlpha(hasSelectedOutput ? 0.9f : 0.58f);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            haptic(toggle);
            if (checked) {
                historyOriginalPreviewIds.add(item.id);
            } else {
                historyOriginalPreviewIds.remove(item.id);
            }
            preview.setText(compactPreview(historyVisibleText(item)));
            previewMode.setText(historyPreviewModeText(item, true, checked));
        });
        toggle.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
        ));
        return toggle;
    }

    private String historyMeta(VoiceHistoryItem item) {
        String date = item.timestampMs > 0
                ? DateFormat.format("MMM d · h:mm a", item.timestampMs).toString()
                : "Recent";
        if (item.isTranslation() && !item.targetLanguage.isEmpty()) {
            return date + " · " + compactLanguageName(item.targetLanguage);
        }
        return date + (item.isCreation() ? " · Created" : " · Dictation");
    }

    private TextView historyOutputButton(VoiceHistoryItem item) {
        TextView button = chip(historyOutputLabel(item.preset, item.expression), v -> showHistoryOutputMenu(v, item));
        button.setTextSize(12);
        button.setTypeface(Fonts.medium(this));
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMaxWidth(dp(180));
        button.setEllipsize(TextUtils.TruncateAt.END);
        Ui.setBackgroundKeepPadding(button, pillBackground(ui.raised, ui.border, 32, 999));
        button.setPadding(dp(11), 0, dp(9), 0);
        Drawable chevron = getDrawable(R.drawable.ic_chevron_down_24);
        if (chevron != null) {
            chevron.setBounds(0, 0, dp(14), dp(14));
            chevron.setTint(colors.text);
            button.setCompoundDrawablePadding(dp(5));
            button.setCompoundDrawables(null, null, chevron, null);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
        );
        params.setMargins(dp(8), dp(1), 0, dp(1));
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton historyOpenButton(VoiceHistoryItem item) {
        ImageButton button = plainIconButton(R.drawable.ic_open_in_full_24, v -> {
            haptic(v);
            Intent intent = new Intent(this, TranscriptDetailActivity.class);
            intent.putExtra(TranscriptDetailActivity.EXTRA_HISTORY_ID, item.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        button.setContentDescription("Open full transcript");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
        params.setMargins(dp(4), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private String historyOutputLabel(String preset, int expression) {
        if (Prefs.PRESET_RAW.equals(preset)) {
            return "Original";
        }
        return Prefs.labelForPreset(this, preset) + " · " + Prefs.expressionLabel(expression);
    }

    private void showHistoryOutputMenu(View anchor, VoiceHistoryItem item) {
        PopupMenu menu = new PopupMenu(this, anchor);
        List<String> variants = new ArrayList<>();
        variants.add(Prefs.PRESET_RAW);
        for (String preset : Prefs.selectablePresetValues(this)) {
            String variant = Prefs.historyVariantKey(preset, Prefs.expressionForPreset(this, preset));
            if (!variants.contains(variant)) {
                variants.add(variant);
            }
        }
        for (String variant : item.outputs.keySet()) {
            if (!variants.contains(variant)) {
                variants.add(variant);
            }
        }
        String selectedVariant = item.selectedVariantKey();
        for (int i = 0; i < variants.size(); i++) {
            String variant = variants.get(i);
            String preset = Prefs.historyVariantPreset(variant);
            int expression = Prefs.historyVariantExpression(variant);
            boolean available = item.hasOutputForVariant(preset, expression);
            String label = historyOutputLabel(preset, expression);
            if (!available) {
                label += " (not created)";
            }
            menu.getMenu().add(0, i, i, label)
                    .setCheckable(true)
                    .setChecked(variant.equals(selectedVariant));
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(menuItem -> {
            int index = menuItem.getItemId();
            if (index < 0 || index >= variants.size()) {
                return false;
            }
            String variant = variants.get(index);
            historyOriginalPreviewIds.remove(item.id);
            Prefs.selectTranscriptHistoryVariant(
                    this,
                    item.id,
                    Prefs.historyVariantPreset(variant),
                    Prefs.historyVariantExpression(variant)
            );
            showHistoryPanel();
            return true;
        });
        menu.show();
    }

    private TextView historyActionButton(String text, View.OnClickListener listener, boolean primary) {
        TextView button = chip(text, listener);
        button.setTextSize(13);
        if (primary) {
            tonal(button);
        }
        button.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
        );
        params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView historyText(String text, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(ui.fg);
        view.setTypeface(bold ? Fonts.semibold(this) : Fonts.regular(this));
        view.setIncludeFontPadding(false);
        return view;
    }

    private String compactPreview(String text) {
        String compact = text == null ? "" : text.replace('\n', ' ').trim();
        while (compact.contains("  ")) {
            compact = compact.replace("  ", " ");
        }
        return compact.isEmpty() ? "(empty)" : compact;
    }

    private void showEmojiPicker() {
        if (keyboardPanel == null || recording || processing || panelAnimating) return;
        settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
        stopDeleteHold();
        stopSpaceCursorTracking();
        clearAutoCorrection();
        clearLastAutoCorrection();
        hideChipStrip();
        historyMode = false;
        setHistoryControlsActive(false);
        keyButtons.clear();
        letterButtons.clear();
        spaceButtons.clear();
        shiftButton = null;
        keyboardPanel.removeAllViews();
        keyboardPanel.setPadding(0, 0, 0, 0);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int searchKeyHeight = landscape ? 32 : metrics.keyHeightDp;
        EmojiPickerView picker = new EmojiPickerView(this, ui.ime, colors.text,
                colors.key, colors.accent, searchKeyHeight, canRememberFromThisField(), new EmojiPickerView.Listener() {
            @Override public boolean onEmoji(String emoji) {
                if (emojiPicker == null || recording || processing) return false;
                InputConnection connection = getCurrentInputConnection();
                if (connection == null) return false;
                clearLastVoiceInsertion();
                return connection.commitText(emoji, 1);
            }
            @Override public void onClose() {
                resetToRegularKeyboard(currentLayer());
            }
            @Override public void onDelete() { deleteOne(); }
        });
        emojiPicker = picker;
        keyboardPanel.addView(picker, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        setKeyboardSurfaceHeight(Math.max(metrics.keyHeightDp * 5 + 40, searchKeyHeight * 4 + 100));
        setStatus("Emoji");
        if (emojiCatalog != null) {
            picker.setCatalog(emojiCatalog);
        } else if (!emojiCatalogLoading) {
            emojiCatalogLoading = true;
            typingExecutor.execute(() -> {
                EmojiCatalog loaded = null;
                try (InputStreamReader reader = new InputStreamReader(
                        getAssets().open("emoji/catalog.tsv"), StandardCharsets.UTF_8)) {
                    loaded = EmojiCatalog.read(reader);
                } catch (IOException ignored) {
                    // Let the picker offer a retry without affecting normal typing.
                }
                EmojiCatalog result = loaded;
                mainHandler.post(() -> {
                    emojiCatalogLoading = false;
                    emojiCatalog = result;
                    if (emojiPicker != null) {
                        if (result != null) emojiPicker.setCatalog(result);
                        else emojiPicker.showLoadError();
                    }
                });
            });
        }
    }

    /**
     * Whether anything typed or dictated here may be kept: emoji recents,
     * transcript history and learned words. Incognito editors set
     * IME_FLAG_NO_PERSONALIZED_LEARNING, and a password is never kept.
     */
    private boolean canRememberFromThisField() {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false;
        return !isPasswordInputType(info.inputType);
    }

    /**
     * History shows past dictations with Copy and Paste, so it stays out of
     * password fields and off the lock screen, where anyone holding the locked
     * phone could otherwise read it.
     */
    private boolean canShowHistory() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        EditorInfo info = getCurrentInputEditorInfo();
        return info == null || !isPasswordInputType(info.inputType);
    }

    private boolean isPasswordInputType(int inputType) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass == InputType.TYPE_CLASS_NUMBER) return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        return inputClass == InputType.TYPE_CLASS_TEXT && isPasswordVariation(variation);
    }

    private void dismissEmojiPicker() {
        emojiPicker = null;
        if (keyboardPanel != null) {
            keyboardPanel.setPadding(dp(metrics.outerInsetDp), 0, dp(metrics.outerInsetDp), 0);
        }
        setKeyboardSurfaceHeight(metrics.keyHeightDp * 4);
    }

    private void setKeyboardSurfaceHeight(int heightDp) {
        if (keyboardSurface == null || keyboardSurface.getLayoutParams() == null) return;
        android.view.ViewGroup.LayoutParams params = keyboardSurface.getLayoutParams();
        if (params.height != dp(heightDp)) {
            params.height = dp(heightDp);
            keyboardSurface.setLayoutParams(params);
        }
    }

    private LinearLayout bottomRow(String modeLabel) {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        String normalizedMode = modeLabel.replace("?", "");
        bottom.addView(keyButton(normalizedMode, 1.4f, v -> toggleSymbolsMode(), true));
        boolean showLanguageKey = chineseAvailable();
        if (showLanguageKey) {
            // Sits immediately right of 123; the space bar gives up the width.
            bottom.addView(languageKey());
        }
        float spaceWeight = symbolsMode ? 5.45f : 5.7f;
        if (showLanguageKey) {
            spaceWeight -= 1.15f;
        }
        TextView emojiKey = keyButton("", 1.1f, v -> showEmojiPicker(), true);
        emojiKey.setBackground(iconKeyBackground(colors.keyAlt, R.drawable.ph_smiley, colors.text));
        emojiKey.setContentDescription("Open emoji keyboard");
        bottom.addView(emojiKey);
        spaceWeight -= 1.1f;
        if (metrics.split) {
            // A single space bar spanning the gap would be unreachable in the
            // middle and would visually bridge the two halves. Give each thumb
            // its own; both carry the same tap and cursor-drag behaviour.
            // Borrow the emoji key's width from the left space bar so adding
            // it leaves the existing split seam and right cluster in place.
            bottom.addView(spaceKey(spaceWeight / 2f - 0.55f));
            bottom.addView(splitSpacer());
            bottom.addView(spaceKey(spaceWeight / 2f + 0.55f));
        } else {
            bottom.addView(spaceKey(spaceWeight));
        }
        if (!symbolsMode) {
            String stop = inputMode.isChinese() ? "。" : ".";
            bottom.addView(keyButton(stop, 0.9f, v -> commitSeparator(stop)));
        } else {
            // The way to the number pad in a code or card field that the app
            // did not mark as numeric.
            TextView numpad = keyButton("1234", 0.9f, v -> showLayer(KeyboardLayerRules.Layer.NUMPAD), true);
            numpad.setContentDescription("Open number pad");
            bottom.addView(numpad);
        }
        bottom.addView(returnKey(1.65f));
        return bottom;
    }

    /**
     * Phone-style number pad: 1-9 over 0, a function column of delete, a spare
     * key and return, and ABC to leave. On a large screen it is centred at a
     * comfortable width rather than split, since a keypad has no two halves.
     */
    private void addNumpadRows(LinearLayout panel) {
        String[] extras = KeyboardLayerRules.numpadExtras(activeInputType);
        String[][] digits = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < digits.length; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (String digit : digits[row]) {
                line.addView(numpadKey(digit, 1f));
            }
            if (row == 0) {
                line.addView(deleteKey());
            } else if (row == 1) {
                line.addView(numpadKey(extras[0], NUMPAD_FUNCTION_WEIGHT));
            } else {
                line.addView(returnKey(NUMPAD_FUNCTION_WEIGHT));
            }
            pad.addView(line);
        }
        LinearLayout last = new LinearLayout(this);
        last.setOrientation(LinearLayout.HORIZONTAL);
        last.addView(numpadKey(extras[1], 1f));
        last.addView(numpadKey("0", 1f));
        last.addView(numpadKey(extras[2], 1f));
        TextView letters = keyButton("ABC", NUMPAD_FUNCTION_WEIGHT,
                v -> showLayer(KeyboardLayerRules.Layer.LETTERS), true);
        letters.setContentDescription("Switch to letters");
        last.addView(letters);
        pad.addView(last);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                metrics.split ? dp(NUMPAD_MAX_WIDTH_DP) : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(pad, params);
    }

    /** A number pad key that types its label, or a blank filler when the label is empty. */
    private TextView numpadKey(String label, float weight) {
        if (label.isEmpty()) {
            TextView blank = keyButton("", weight, v -> {
            }, true);
            blank.setClickable(false);
            blank.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return blank;
        }
        String value = KeyboardLayerRules.SPACE.equals(label) ? " " : label;
        TextView key = keyButton(label, weight, v -> commitNumpadText(value));
        if (label.length() == 1) {
            key.setTextSize(22);
        }
        return key;
    }

    /**
     * Number pad keys type exactly their label. They bypass commitKey on
     * purpose: in Chinese 9-key mode it would feed 2-9 to the pinyin composer,
     * and commitSeparator would turn "." into "。".
     */
    private void commitNumpadText(String text) {
        clearBarMessage();
        if (recording || processing) {
            return;
        }
        lastShiftTapMs = 0;
        clearPhraseUndo();
        retirePasteOffer();
        clearLastAutoCorrection();
        clearAutoCorrection();
        commitText(text);
    }

    private LinearLayout letterMiddleRow() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        if (metrics.split) {
            // Samsung keeps the home row staggered by roughly half a key on
            // both outer edges. This also keeps A and L from drifting back to
            // the bezels after the split clusters are inset.
            outer.addView(edgeDeadZone(metrics.outerInsetDp));
        } else {
            // On a phone these edge zones turn near-misses at the bezel into A
            // and L taps without changing the visible row alignment.
            outer.addView(edgeDeadZone(10));
            outer.addView(edgeHitZone("a", 14));
        }
        outer.addView(keyRow("asdfghjkl", KeyboardGeometry.Row.MIDDLE), new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        if (metrics.split) {
            outer.addView(edgeDeadZone(metrics.outerInsetDp));
        } else {
            outer.addView(edgeHitZone("l", 14));
            outer.addView(edgeDeadZone(10));
        }
        return outer;
    }

    private View edgeDeadZone(int widthDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(widthDp),
                keyHeightPx()
        ));
        return view;
    }

    private View edgeHitZone(String key, int widthDp) {
        View view = new View(this);
        view.setClickable(true);
        view.setOnClickListener(v -> {
            haptic(v);
            commitKey(key);
        });
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(widthDp),
                keyHeightPx()
        ));
        return view;
    }

    private LinearLayout keyRow(String chars) {
        return keyRow(chars, KeyboardGeometry.Row.TOP);
    }

    private LinearLayout keyRow(String chars, KeyboardGeometry.Row rowId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int splitAt = splitIndexFor(rowId, chars.length());
        for (int i = 0; i < chars.length(); i++) {
            if (i == splitAt) {
                row.addView(splitSpacer());
            }
            row.addView(letterKeyButton(String.valueOf(chars.charAt(i)), 1f));
        }
        return row;
    }

    private LinearLayout keyRow(String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int splitAt = splitIndexFor(KeyboardGeometry.Row.SYMBOLS, labels.length);
        for (int i = 0; i < labels.length; i++) {
            if (i == splitAt) {
                row.addView(splitSpacer());
            }
            row.addView(keyButton(labels[i], 1f, v -> commitKey(((TextView) v).getText().toString())));
        }
        return row;
    }

    private void showIdleChips() {
        if (emojiPicker != null) {
            hideChipStrip();
            renderBar();
            return;
        }
        // The nine-key side column follows the composition: spellings or punctuation.
        refreshKeypadSide();
        // Candidates outrank everything: while composing, the strip is the only
        // way to choose a character.
        if (showPinyinCandidates()) {
            renderBar();
            return;
        }
        // The last insertion's own bar (Inserted, or Kept your words) comes next.
        if (insertionBarLive()) {
            hideChipStrip();
            renderBar();
            return;
        }
        if (showRetryChip()) {
            renderBar();
            return;
        }
        if (showSpellingSuggestionChip()) {
            renderBar();
            return;
        }
        if (showAutoCompleteChips()) {
            renderBar();
            return;
        }
        if (showModelNoticeChip()) {
            renderBar();
            return;
        }
        if (showPasteChip()) {
            renderBar();
            return;
        }
        hideChipStrip();
        renderBar();
    }

    /** Whether the bar should show the last insertion's Inserted or Kept-your-words state. */
    private boolean insertionBarLive() {
        if (recording || processing || historyMode || retoneMode || lastVoiceInsertedText.isEmpty()) {
            return false;
        }
        return insertedFresh || !lastVoiceHeldBack.isEmpty();
    }

    /** Shows the bar the service's state calls for. The service owns every flag it reads. */
    private void renderBar() {
        if (idleBar == null) {
            return;
        }
        VoiceBar.State state = new VoiceBar.State();
        state.recording = recording;
        state.processing = processing;
        state.historyMode = historyMode;
        state.retoneMode = retoneMode;
        state.composingCandidates = pinyinCandidateStripExpanded;
        state.keptWords = insertionBarLive() && !lastVoiceHeldBack.isEmpty();
        state.insertedFresh = insertionBarLive() && insertedFresh;
        state.message = !barMessage.isEmpty();
        state.hasSuggestions = suggestionsShowing;
        VoiceBar.Mode mode = VoiceBar.mode(state);
        barMode = mode;

        idleBar.setVisibility(mode == VoiceBar.Mode.IDLE ? View.VISIBLE : View.GONE);
        boolean strip = mode == VoiceBar.Mode.SUGGESTIONS || mode == VoiceBar.Mode.CANDIDATES;
        suggestionBar.setVisibility(strip ? View.VISIBLE : View.GONE);
        micButton.setVisibility(mode == VoiceBar.Mode.SUGGESTIONS ? View.VISIBLE : View.GONE);
        listeningBar.setVisibility(mode == VoiceBar.Mode.LISTENING ? View.VISIBLE : View.GONE);
        workingBar.setVisibility(mode == VoiceBar.Mode.WORKING ? View.VISIBLE : View.GONE);
        if (workingCancelButton != null) {
            workingCancelButton.setVisibility(mode == VoiceBar.Mode.WORKING && speechCancel != null
                    ? View.VISIBLE : View.GONE);
        }
        boolean done = mode == VoiceBar.Mode.INSERTED || mode == VoiceBar.Mode.KEPT_WORDS;
        doneBar.setVisibility(done ? View.VISIBLE : View.GONE);
        boolean header = mode == VoiceBar.Mode.HISTORY || mode == VoiceBar.Mode.RETONE;
        headerBar.setVisibility(header ? View.VISIBLE : View.GONE);
        syncRecordingPanel(mode);

        switch (mode) {
            case IDLE:
                boolean message = !barMessage.isEmpty();
                styleChip.setVisibility(message ? View.GONE : View.VISIBLE);
                messageText.setVisibility(message ? View.VISIBLE : View.GONE);
                messageText.setText(barMessage);
                refreshStyleChips();
                topHistoryButton.setVisibility(canShowHistory() ? View.VISIBLE : View.GONE);
                break;
            case LISTENING:
                listeningLabel.setText(VoiceBar.listeningLabel(translationCapture, creationCapture, instructionCapture));
                refreshStyleChips();
                break;
            case INSERTED:
            case KEPT_WORDS:
                renderDoneBar(mode);
                break;
            case HISTORY:
                headerTitle.setText(barMessage.isEmpty() ? "History" : barMessage);
                headerAction.setText(compactBar ? "" : "Keyboard");
                setPillIcon(headerAction, R.drawable.ph_keyboard, ui.fg);
                headerAction.setContentDescription("Back to the keyboard");
                headerAction.setOnClickListener(v -> {
                    haptic(v);
                    hideHistoryPanelAnimated();
                });
                break;
            case RETONE:
                headerTitle.setText("Retone the last text");
                headerAction.setText("Cancel");
                setPillIcon(headerAction, R.drawable.ph_x, ui.fg);
                headerAction.setContentDescription("Cancel retone");
                headerAction.setOnClickListener(v -> {
                    haptic(v);
                    closeRetoneOverlay("Retone canceled");
                });
                break;
            default:
                break;
        }
    }

    private void setPillIcon(TextView pill, int iconRes, int color) {
        Drawable icon = getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setBounds(0, 0, dp(18), dp(18));
            icon.setTint(color);
        }
        pill.setCompoundDrawables(icon, null, null, null);
        pill.setCompoundDrawablePadding(pill.getText().length() == 0 ? 0 : dp(6));
    }

    /** "Inserted · Clean · Natural" with Undo, Original and Feedback; or "Kept your words" with Use cleaned. */
    private void renderDoneBar(VoiceBar.Mode mode) {
        doneActions.removeAllViews();
        boolean kept = mode == VoiceBar.Mode.KEPT_WORDS;
        doneIcon.setImageResource(kept ? R.drawable.ph_info_fill : R.drawable.ph_check_circle_fill);
        doneIcon.setColorFilter(kept ? ui.info : ui.success);
        String style = VoiceBar.styleLabel(labelForPreset(lastVoicePreset), lastVoiceExpression);
        if (kept) {
            boolean original = HELD_BACK_ORIGINAL.equals(lastVoiceHeldBackReason);
            doneTitle.setText(original ? "Your own words" : "Kept your words");
            doneSubtitle.setText(original ? "Switched to the original" : "Mixed languages: tidy-up held back");
        } else {
            doneTitle.setText(lastVoiceOperation.equals(VoiceHistoryItem.OPERATION_TRANSLATION)
                    ? "Translated"
                    : lastVoiceOperation.equals(VoiceHistoryItem.OPERATION_CREATION) ? "Created" : "Inserted");
            doneSubtitle.setText(style);
        }
        // 0.8.6: the caret says what 0.7.x's Retone chip said: tap to retone in another style.
        Drawable caret = kept ? null : getDrawable(R.drawable.ph_caret_down);
        if (caret != null) {
            caret = caret.mutate();
            caret.setBounds(0, 0, dp(12), dp(12));
            caret.setTint(ui.subtle);
        }
        doneSubtitle.setCompoundDrawables(null, null, caret, null);
        doneSubtitle.setCompoundDrawablePadding(dp(3));
        doneLabels.setContentDescription(doneTitle.getText() + ", " + doneSubtitle.getText()
                + ". Double-tap to retone in another style");
        doneLabels.setClickable(true);
        doneLabels.setOnClickListener(v -> {
            haptic(v);
            openRetoneOverlay();
        });

        if (kept) {
            TextView useCleaned = tonalPill(compactBar ? "" : "Use cleaned", R.drawable.ph_sparkle, 34);
            useCleaned.setTextSize(12.5f);
            useCleaned.setContentDescription(HELD_BACK_ORIGINAL.equals(lastVoiceHeldBackReason)
                    ? "Use the tidied text again"
                    : "Kept your words because of mixed languages. Use the tidied text instead");
            useCleaned.setOnClickListener(v -> {
                haptic(v);
                useHeldBackCleanup();
            });
            doneActions.addView(useCleaned, doneActionParams());
        } else {
            TextView undo = donePill("Undo", R.drawable.ph_arrow_counter_clockwise);
            undo.setContentDescription("Undo: remove the inserted text");
            undo.setOnClickListener(v -> {
                haptic(v);
                undoLastVoiceInsertion();
            });
            doneActions.addView(undo, doneActionParams());
            if (originalAvailable()) {
                TextView original = donePill("Original", R.drawable.ph_quotes);
                original.setContentDescription("Use your original words instead");
                original.setOnClickListener(v -> {
                    haptic(v);
                    useOriginalWords();
                });
                doneActions.addView(original, doneActionParams());
            }
        }
        if (!lastVoiceHistoryId.isEmpty()) {
            TextView feedback = donePill("Feedback", R.drawable.ph_chat_circle);
            if (!kept) {
                // The Inserted bar's one tonal action, as in the mockup.
                Ui.setBackgroundKeepPadding(feedback, pillBackground(ui.accentTint, ui.accentTintBorder, 34, 999));
                feedback.setTextColor(ui.accentInk);
                setPillIcon(feedback, R.drawable.ph_chat_circle, ui.accentInk);
            }
            feedback.setContentDescription("Feedback: open this dictation in History");
            feedback.setOnClickListener(v -> {
                haptic(v);
                openHistoryItem(lastVoiceHistoryId);
            });
            doneActions.addView(feedback, doneActionParams());
        }
    }

    private TextView donePill(String text, int iconRes) {
        TextView pill = outlinePill(compactBar ? "" : text, iconRes);
        pill.setTextSize(12.5f);
        pill.setPadding(dp(compactBar ? 12 : 9), 0, dp(compactBar ? 12 : 10), 0);
        pill.setCompoundDrawablePadding(compactBar ? 0 : dp(4));
        Drawable[] icons = pill.getCompoundDrawables();
        if (icons[0] != null) {
            icons[0].setBounds(0, 0, dp(16), dp(16));
        }
        return pill;
    }

    private LinearLayout.LayoutParams doneActionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
        );
        params.setMargins(dp(3), 0, 0, 0);
        return params;
    }

    /** Original makes sense only for a dictation whose tidy-up changed the words. */
    private boolean originalAvailable() {
        if (!VoiceHistoryItem.OPERATION_DICTATION.equals(lastVoiceOperation) || lastVoiceRawTranscript.isEmpty()) {
            return false;
        }
        String original = finishDictatedText(lastVoiceRawTranscript, Prefs.allPhraseReplacements(this));
        return !original.trim().equals(lastVoiceInsertedText.trim());
    }

    /**
     * Swaps the tidied text for the user's own words, over the verified last insertion
     * only, and offers the tidy-up back as "Use cleaned".
     */
    private void useOriginalWords() {
        if (recording || processing || lastVoiceInsertedText.isEmpty()) {
            return;
        }
        String cleaned = lastVoiceInsertedText.trim();
        String raw = lastVoiceRawTranscript;
        String preset = lastVoicePreset;
        int expression = lastVoiceExpression;
        String historyId = lastVoiceHistoryId;
        String operation = lastVoiceOperation;
        String original = finishDictatedText(raw, Prefs.allPhraseReplacements(this));
        String replacement = replaceLastVoiceInsertion(lastVoiceInsertedText, original);
        if (replacement.isEmpty()) {
            clearLastVoiceInsertion();
            setStatus("Field changed - nothing replaced");
            return;
        }
        rememberLastVoiceInsertion(raw, replacement, preset, expression, operation, "", historyId);
        lastVoiceHeldBack = cleaned;
        lastVoiceHeldBackReason = HELD_BACK_ORIGINAL;
        insertedFresh = false;
        showIdleChips();
        announce("Original words inserted");
    }

    /** Removes the verified last insertion; it stays in History. */
    private void undoLastVoiceInsertion() {
        if (recording || processing || lastVoiceInsertedText.isEmpty()) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        String old = lastVoiceInsertedText;
        boolean removed = false;
        if (connection != null) {
            int cursor = collapsedCursor(connection);
            DeliveryGuard.Verdict verdict = DeliveryGuard.mayReplace(
                    new DeliveryGuard.Insertion(lastVoiceEditor, lastVoiceSelectionEnd, old),
                    currentDeliveryTarget(),
                    cursor >= 0 ? 0 : -1,
                    cursor,
                    connection.getTextBeforeCursor(old.length(), 0)
            );
            if (verdict == DeliveryGuard.Verdict.OK) {
                connection.beginBatchEdit();
                try {
                    removed = connection.deleteSurroundingText(old.length(), 0);
                } finally {
                    connection.endBatchEdit();
                }
            }
        }
        boolean saved = !lastVoiceHistoryId.isEmpty();
        clearLastVoiceInsertion();
        clearAutoCorrection();
        updateAutoCapitalization();
        setStatus(removed
                ? (saved ? "Removed - still in History" : "Removed")
                : "Field changed - nothing removed");
    }

    private void openHistoryItem(String historyId) {
        if (historyId == null || historyId.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, TranscriptDetailActivity.class);
        intent.putExtra(TranscriptDetailActivity.EXTRA_HISTORY_ID, historyId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void expireInsertedBar() {
        if (!insertedFresh) {
            return;
        }
        insertedFresh = false;
        if (!recording && !processing && chipStrip != null) {
            showIdleChips();
        }
    }

    /** A short message in place of the style chip, for {@link VoiceBar#MESSAGE_MS}. */
    private void showBarMessage(String message) {
        barMessage = message;
        mainHandler.removeCallbacks(barMessageExpiry);
        mainHandler.postDelayed(barMessageExpiry, VoiceBar.MESSAGE_MS);
        renderBar();
    }

    private void clearBarMessage() {
        if (barMessage.isEmpty()) {
            return;
        }
        barMessage = "";
        mainHandler.removeCallbacks(barMessageExpiry);
        renderBar();
    }

    private void announce(String text) {
        if (keyboardSurface != null) {
            keyboardSurface.announceForAccessibility(text);
        }
    }

    /** Stop finishes whichever capture is running: dictation, translation, creation or a voice edit. */
    private void stopCurrentCapture() {
        if (!recording || processing) {
            return;
        }
        if (translationCapture) {
            toggleTranslationCapture();
        } else if (creationCapture) {
            toggleCreationCapture();
        } else if (instructionCapture) {
            toggleInstructionCapture();
        } else {
            toggleVoiceCapture();
        }
    }

    /** Translate, create, voice edit, spelling and settings: one tap away, never in the way. */
    private void showOverflowMenu(View anchor) {
        if (recording || processing) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        if (Prefs.translationEnabled(this)) {
            menu.getMenu().add(0, MENU_TRANSLATE, 0, "Translate to " + compactLanguageName(Prefs.translationTargetLanguage(this)));
        }
        menu.getMenu().add(0, MENU_CREATE, 1, "Create text by voice");
        menu.getMenu().add(0, MENU_INSTRUCTION, 2, "Edit this text by voice");
        menu.getMenu().add(0, MENU_SPELLING, 3, "Fix spelling");
        if (clipboardHasText()) {
            menu.getMenu().add(0, MENU_PASTE, 4, "Paste clipboard");
        }
        // 0.8.7: one tap between the two Chinese layouts, as Sogou offers. The
        // other one from the layout on screen, or from the second keyboard in English.
        final boolean keypadNext = !(inputMode.isChinese() ? inputMode : secondaryKeyboard()).isKeypad();
        if (chineseAvailable()) {
            menu.getMenu().add(1, MENU_CHINESE_LAYOUT, 5, keypadNext
                    ? "中文 九宫格 (nine-key)" : "中文 全键 (full keyboard)");
        }
        menu.getMenu().add(1, MENU_SETTINGS, 6, "VoiceFlow settings");
        menu.setOnMenuItemClickListener(item -> {
            haptic(anchor);
            switch (item.getItemId()) {
                case MENU_TRANSLATE:
                    toggleTranslationCapture();
                    return true;
                case MENU_CREATE:
                    toggleCreationCapture();
                    return true;
                case MENU_INSTRUCTION:
                    toggleInstructionCapture();
                    return true;
                case MENU_SPELLING:
                    correctSpellingOnly();
                    return true;
                case MENU_PASTE:
                    pasteClipboard();
                    return true;
                case MENU_CHINESE_LAYOUT:
                    chooseKeyboard(keypadNext ? InputMode.CHINESE_KEYPAD : InputMode.CHINESE_QWERTY);
                    return true;
                case MENU_SETTINGS:
                    openSettings();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    /**
     * Says once that a chosen cloud model disappeared and what replaced it.
     * Tapping opens settings; otherwise it goes when the keyboard next hides.
     */
    private boolean showModelNoticeChip() {
        if (modelNotice.isEmpty()) {
            String pending = Prefs.takeModelSwitchNotice(this);
            modelNotice = pending == null ? "" : pending;
        }
        if (modelNotice.isEmpty()
                || chipStrip == null
                || recording
                || processing
                || historyMode
                || retoneMode
                || emojiPicker != null) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        TextView notice = chip(modelNotice, v -> {
            modelNotice = "";
            modelNoticeShown = false;
            openSettings();
        });
        notice.setContentDescription(modelNotice + ". Open settings");
        chipStrip.addView(notice);
        modelNoticeShown = true;
        return true;
    }

    // ------------------------------------------------------------------ Paste

    private ClipboardManager clipboard() {
        return (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /** Listens for copies only while the keyboard is on screen. */
    private void watchClipboard(boolean watch) {
        ClipboardManager clipboard = clipboard();
        if (clipboard == null) {
            return;
        }
        if (clipListener == null) {
            clipListener = () -> {
                refreshPasteOffer(true);
                if (!recording && !processing && !historyMode && !retoneMode) {
                    showIdleChips();
                }
            };
        }
        clipboard.removePrimaryClipChangedListener(clipListener);
        if (watch) {
            clipboard.addPrimaryClipChangedListener(clipListener);
        }
    }

    /**
     * Decides from the clipboard's description, never its contents, whether a
     * Paste chip is worth offering. Outside password fields that means text
     * copied in the last two minutes. In a password field any copied text is
     * offered, since a password manager copies first and the user navigates
     * after. Nothing is offered on the lock screen.
     */
    private void refreshPasteOffer(boolean changedJustNow) {
        pasteOfferClip = -1;
        pasteOfferCopiedAt = 0;
        pasteOfferInPasswordField = false;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            return;
        }
        ClipboardManager clipboard = clipboard();
        ClipDescription description = clipboard == null ? null : clipboard.getPrimaryClipDescription();
        if (description == null || !description.hasMimeType("text/*")) {
            return;
        }
        // getTimestamp() is the copy time in System.currentTimeMillis(). A copy
        // seen happening while the keyboard is open is dated by the keyboard
        // itself, which also covers a system that leaves the timestamp unset.
        pasteOfferClip = Math.max(0, description.getTimestamp());
        pasteOfferCopiedAt = changedJustNow ? System.currentTimeMillis() : description.getTimestamp();
        EditorInfo info = getCurrentInputEditorInfo();
        pasteOfferInPasswordField = info != null && isPasswordInputType(info.inputType);
    }

    private boolean pasteOfferIsLive() {
        if (pasteOfferClip < 0) {
            return false;
        }
        if (pasteOfferInPasswordField) {
            return true;
        }
        if (pasteOfferClip == pasteHandledClip || pasteOfferCopiedAt <= 0) {
            return false;
        }
        long age = System.currentTimeMillis() - pasteOfferCopiedAt;
        return age >= 0 && age <= PASTE_CHIP_FRESH_MS;
    }

    /** The lowest-priority chip: anything else the strip has to say wins. */
    private boolean showPasteChip() {
        if (chipStrip == null
                || recording
                || processing
                || historyMode
                || retoneMode
                || emojiPicker != null
                || composingPinyin()
                || !pasteOfferIsLive()) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        TextView paste = chip("Paste", v -> pasteClipboard());
        paste.setTag(PASTE_CHIP_TAG);
        paste.setContentDescription("Paste copied text");
        chipStrip.addView(paste);
        mainHandler.removeCallbacks(pasteExpiryRunnable);
        if (!pasteOfferInPasswordField) {
            long remaining = PASTE_CHIP_FRESH_MS - (System.currentTimeMillis() - pasteOfferCopiedAt);
            mainHandler.postDelayed(pasteExpiryRunnable, Math.max(0, remaining) + 50);
        }
        return true;
    }

    private void expirePasteChip() {
        if (chipStrip != null && chipStrip.findViewWithTag(PASTE_CHIP_TAG) != null) {
            showIdleChips();
        }
    }

    /** Typing past the offer retires it; a password field keeps offering. */
    private void retirePasteOffer() {
        if (!pasteOfferInPasswordField) {
            pasteHandledClip = pasteOfferClip;
        }
    }

    private boolean clipboardHasText() {
        ClipboardManager clipboard = clipboard();
        ClipDescription description = clipboard == null ? null : clipboard.getPrimaryClipDescription();
        return description != null && description.hasMimeType("text/*");
    }

    /**
     * Types the clipboard's text exactly as copied. This works even in apps
     * that block their own paste menu, because the keyboard commits it as
     * typed text.
     */
    private void pasteClipboard() {
        if (recording || processing || retoneMode) {
            return;
        }
        ClipboardManager clipboard = clipboard();
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0
                ? null
                : clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            setStatus("Nothing to paste");
            return;
        }
        pasteHandledClip = pasteOfferClip;
        if (historyMode) {
            hideHistoryPanel();
        }
        clearPhraseUndo();
        clearLastAutoCorrection();
        clearAutoCorrection();
        commitText(text.toString());
        updateAutoCapitalization();
        showIdleChips();
        setStatus("Pasted");
    }

    /**
     * Swaps the user's own words, inserted after the drift check held the tidy-up back,
     * for that tidy-up, but only over the verified last insertion. Otherwise the
     * tidy-up is kept in History and nothing in the field is touched.
     */
    private void useHeldBackCleanup() {
        if (recording || processing || lastVoiceHeldBack.isEmpty()) {
            return;
        }
        String cleaned = lastVoiceHeldBack;
        String raw = lastVoiceRawTranscript;
        String preset = lastVoicePreset;
        int expression = lastVoiceExpression;
        String historyId = lastVoiceHistoryId;
        String operation = lastVoiceOperation;
        String replacement = replaceLastVoiceInsertion(lastVoiceInsertedText, cleaned);
        Prefs.updateTranscriptHistory(this, historyId, raw, cleaned, preset, expression);
        Prefs.clearHeldBackCleanup(this, historyId);
        if (replacement.isEmpty()) {
            clearLastVoiceInsertion();
            setStatus("Field changed - tidied text saved in History");
            return;
        }
        rememberLastVoiceInsertion(raw, replacement, preset, expression, operation, "", historyId);
        showIdleChips();
        setStatus("Tidied text inserted");
    }

    private boolean showSpellingSuggestionChip() {
        if (chipStrip == null || recording || processing || pendingAutoCorrectReplacement.isEmpty()) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        TextView original = chip(pendingAutoCorrectWord, v -> rejectPendingAutoCorrection());
        original.setContentDescription("Keep " + pendingAutoCorrectWord);
        chipStrip.addView(original);

        TextView best = chip(pendingAutoCorrectReplacement, v -> applySpellingSuggestion());
        tonal(best);
        best.setContentDescription("Correct to " + pendingAutoCorrectReplacement);
        chipStrip.addView(best);

        String alternate = alternateAutoCorrection();
        if (!alternate.isEmpty()) {
            TextView alt = chip(alternate, v -> applySpellingSuggestion(alternate));
            chipStrip.addView(alt);
        }
        return true;
    }

    private boolean showAutoCompleteChips() {
        if (chipStrip == null || recording || processing || historyMode || pendingAutoCompleteSuggestions.isEmpty()) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        for (String suggestion : pendingAutoCompleteSuggestions) {
            chipStrip.addView(chip(suggestion, v -> applyAutoCompleteSuggestion(((TextView) v).getText().toString())));
        }
        return true;
    }

    private String defaultPreset() {
        return Prefs.activePreset(this);
    }

    private int defaultExpression() {
        return Prefs.expressionForPreset(this, defaultPreset());
    }

    /** Back to the saved default: after a message, a cancel, or the keyboard going away. */
    private void resetChoiceToDefault() {
        String preset = defaultPreset();
        choice.reset(preset, Prefs.expressionForPreset(this, preset));
        refreshStyleChips();
    }

    private boolean styleSheetOpen() {
        return voiceStyleOverlay != null && voiceStyleOverlay.getVisibility() == View.VISIBLE;
    }

    private void toggleStyleSheet() {
        if (processing || instructionCapture) {
            return;
        }
        if (styleSheetOpen()) {
            hideVoiceStyleOverlay();
        } else {
            showVoiceStyleOverlay();
        }
    }

    /**
     * The style and expression sheet, drawn inside the keyboard: the pinned
     * styles, All styles, the expression slider, Make default and Dictate. A
     * choice here is for one message; only Make default saves it. While
     * recording it sits under the listening bar; otherwise it covers the bar too.
     */
    private void showVoiceStyleOverlay() {
        if (voiceStyleOverlay == null) {
            return;
        }
        boolean entering = voiceStyleOverlay.getVisibility() != View.VISIBLE;
        if (entering) {
            // The recording panel's More opens straight onto every style.
            allStylesOpen = openAllStylesNext;
        }
        openAllStylesNext = false;
        int keptScrollY = !entering && voiceStyleScroller != null ? voiceStyleScroller.getScrollY() : 0;
        voiceStyleOverlay.animate().cancel();
        voiceStyleOverlay.removeAllViews();
        voiceStyleOverlay.setOrientation(LinearLayout.VERTICAL);
        voiceStyleOverlay.setPadding(dp(16), dp(2), dp(8), dp(2));
        GradientDrawable sheetBackground = new GradientDrawable();
        sheetBackground.setColor(ui.surface);
        float r = dp(16);
        sheetBackground.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetBackground.setStroke(Math.max(1, dp(1)), ui.border);
        voiceStyleOverlay.setBackground(sheetBackground);
        FrameLayout.LayoutParams overlayParams = (FrameLayout.LayoutParams) voiceStyleOverlay.getLayoutParams();
        // Under the listening bar while recording, so Stop stays in reach; otherwise over
        // the bar too. Exactly the keyboard's height either way, so the app never jumps.
        int top = recording ? dp(BAR_HEIGHT_DP) : 0;
        int surface = keyboardSurface != null && keyboardSurface.getLayoutParams() != null
                && keyboardSurface.getLayoutParams().height > 0
                ? keyboardSurface.getLayoutParams().height
                : dp(metrics.keyHeightDp * 4);
        int height = dp(BAR_HEIGHT_DP) + surface - top;
        if (overlayParams != null && (overlayParams.topMargin != top || overlayParams.height != height)) {
            overlayParams.topMargin = top;
            overlayParams.height = height;
            voiceStyleOverlay.setLayoutParams(overlayParams);
        }

        // ---- header: the title and Close
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String title = compactBar ? "Style"
                : retoneMode ? "Retone in another style"
                : translationCapture ? "Style for this translation"
                        : creationCapture ? "Style for this text" : "Style for this message";
        TextView titleView = barText(title, 16, true, ui.fg);
        heading(titleView);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // All styles (n) sits in the header, so the pinned chips and the slider stay in view.
        List<PromptProfile> styles = Prefs.promptProfiles(this);
        TextView all = barText("All styles " + styles.size(), 13, true, ui.accentInk);
        Drawable caret = getDrawable(allStylesOpen ? R.drawable.ph_caret_down : R.drawable.ph_caret_right);
        if (caret != null) {
            caret = caret.mutate();
            caret.setBounds(0, 0, dp(14), dp(14));
            caret.setTint(ui.accentInk);
            all.setCompoundDrawables(null, null, caret, null);
            all.setCompoundDrawablePadding(dp(4));
        }
        all.setMinHeight(dp(48));
        all.setPadding(dp(10), 0, dp(6), 0);
        all.setClickable(true);
        all.setContentDescription((allStylesOpen ? "Hide" : "Show") + " all " + styles.size() + " styles");
        all.setOnClickListener(v -> {
            haptic(v);
            allStylesOpen = !allStylesOpen;
            showVoiceStyleOverlay();
        });
        header.addView(all, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
        ));
        ImageButton close = iconButton(R.drawable.ph_x, retoneMode ? "Cancel retone" : "Close styles", v -> {
            haptic(v);
            if (retoneMode) {
                closeRetoneOverlay("Retone canceled");
            } else {
                hideVoiceStyleOverlay();
            }
        });
        header.addView(close);
        voiceStyleOverlay.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        // ---- the scrolling middle
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        voiceStyleScroller = scroll;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, dp(8), dp(4));
        scroll.addView(body);

        List<String> ids = new ArrayList<>();
        List<String> funIds = new ArrayList<>();
        for (PromptProfile style : styles) {
            ids.add(style.id);
            if (Prefs.isFunVoiceStyle(style.id)) {
                funIds.add(style.id);
            }
        }
        FlowRow pinned = new FlowRow(this, dp(8), dp(0));
        pinned.setContentDescription("On the bar");
        for (String id : StylePins.onBar(ids, funIds, Prefs.pinnedStyles(this), choice.preset)) {
            pinned.addView(sheetStyleChip(Prefs.promptProfile(this, id)), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48)
            ));
        }
        body.addView(pinned);
        if (allStylesOpen) {
            List<String> pins = StylePins.onBar(ids, funIds, Prefs.pinnedStyles(this), null);
            for (PromptProfile style : styles) {
                body.addView(allStylesRow(style, pins, ids, funIds));
            }
        }

        if (!instructionCapture) {
            // The sheet's body: the keyboard's width less its own and the sheet's padding.
            body.addView(expressionControl(false, getResources().getConfiguration().screenWidthDp - 40));
        }
        voiceStyleOverlay.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        // ---- footer: Make default, and the one primary action. While recording the
        // listening bar holds Stop and Close is enough, so the slider gets the room.
        if (recording && !retoneMode) {
            finishShowingStyleSheet(entering, scroll, keptScrollY, title);
            return;
        }
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, 0, dp(8), 0);
        // Make default when the choice differs; otherwise say what the default is.
        boolean canMakeDefault = !retoneMode && choice.differsFrom(defaultPreset(), defaultExpression());
        String defaultLabel = VoiceBar.styleLabel(labelForPreset(defaultPreset()), defaultExpression());
        TextView makeDefault = barText(canMakeDefault ? "Make default"
                        : retoneMode ? "Your default stays " + labelForPreset(defaultPreset())
                        : "Default: " + defaultLabel,
                canMakeDefault ? 14 : 12.5f, canMakeDefault, canMakeDefault ? ui.muted : ui.subtle);
        makeDefault.setMinHeight(dp(48));
        makeDefault.setMaxLines(2);
        makeDefault.setPadding(dp(2), 0, dp(12), 0);
        makeDefault.setClickable(canMakeDefault);
        makeDefault.setContentDescription(canMakeDefault
                ? "Make " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression) + " your default"
                : "Your default is " + defaultLabel);
        makeDefault.setOnClickListener(v -> {
            haptic(v);
            makeChoiceDefault();
        });
        footer.addView(makeDefault, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView primary;
        if (retoneMode) {
            primary = tonalPill("Apply", R.drawable.ph_sparkle, 40);
            primary.setContentDescription("Apply " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression));
            primary.setOnClickListener(v -> {
                haptic(v);
                applyRetone();
            });
        } else if (recording) {
            primary = tonalPill("Done", R.drawable.ph_check, 40);
            primary.setContentDescription("Done: keep listening with this style");
            primary.setOnClickListener(v -> {
                haptic(v);
                hideVoiceStyleOverlay();
            });
        } else {
            primary = tonalPill("Dictate", R.drawable.ph_microphone, 40);
            primary.setContentDescription("Dictate with " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression));
            primary.setOnClickListener(v -> {
                haptic(v);
                hideVoiceStyleOverlay();
                toggleVoiceCapture();
            });
        }
        Ui.setBackgroundKeepPadding(primary, pillBackground(ui.accentTint, ui.accentTintBorder, 40, 10));
        footer.addView(primary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
        ));
        voiceStyleOverlay.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        finishShowingStyleSheet(entering, scroll, keptScrollY, title);
    }

    private void finishShowingStyleSheet(boolean entering, ScrollView scroll, int keptScrollY, String title) {
        voiceStyleOverlay.setVisibility(View.VISIBLE);
        voiceStyleOverlay.setClickable(true);
        scroll.post(() -> scroll.scrollTo(0, keptScrollY));
        if (entering) {
            voiceStyleOverlay.setAlpha(0f);
            voiceStyleOverlay.animate().alpha(1f).setDuration(140).start();
            announce(title);
        } else {
            voiceStyleOverlay.setAlpha(1f);
        }
        renderBar();
    }

    private static void heading(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
        }
    }

    /** The slider's spoken value: "Natural" (Android 11+), or "Expression: Natural" before. */
    private static void stateDescription(View view, String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setContentDescription("Expression");
            view.setStateDescription(value);
        } else {
            view.setContentDescription("Expression: " + value);
        }
    }

    private TextView sheetLabel(String text) {
        TextView label = barText(text, 12.5f, true, ui.muted);
        heading(label);
        label.setPadding(0, dp(2), 0, dp(2));
        return label;
    }

    /** A style chip in the sheet: tonal with a tick when chosen, outlined otherwise. */
    private TextView sheetStyleChip(PromptProfile style) {
        boolean selected = style.id.equals(choice.preset);
        TextView chip = selected
                ? tonalPill(style.name, R.drawable.ph_check, 36)
                : outlinePill(style.name, 0);
        chip.setTextSize(13.5f);
        chip.setTypeface(selected ? Fonts.semibold(this) : Fonts.medium(this));
        chip.setPadding(dp(selected ? 12 : 14), 0, dp(14), 0);
        chip.setContentDescription("Style " + style.name + (selected ? ", chosen" : ""));
        chip.setOnClickListener(v -> pickStyle(style.id, v));
        return chip;
    }

    /** One style in All styles: tap to choose it, the pin to put it on the bar or take it off. */
    private View allStylesRow(PromptProfile style, List<String> pins, List<String> ids, List<String> funIds) {
        boolean selected = style.id.equals(choice.preset);
        boolean isPinned = pins.contains(style.id);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView name = barText(style.displayName(), 14, selected, selected ? ui.accentInk : ui.fg);
        name.setMinHeight(dp(48));
        name.setClickable(true);
        name.setContentDescription("Style " + style.name + (selected ? ", chosen" : ""));
        name.setOnClickListener(v -> pickStyle(style.id, v));
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton pin = iconButton(isPinned ? R.drawable.ph_push_pin_fill : R.drawable.ph_push_pin,
                (isPinned ? "Take " : "Put ") + style.name + (isPinned ? " off the bar" : " on the bar"), v -> {
                    haptic(v);
                    Prefs.setPinnedStyles(this, StylePins.toggle(StylePins.onBar(ids, funIds, Prefs.pinnedStyles(this), null), style.id));
                    showVoiceStyleOverlay();
                });
        pin.setColorFilter(isPinned ? ui.accent : ui.subtle);
        row.addView(pin);
        return row;
    }

    /** Chooses a style for this message only, at that style's own saved expression. */
    private void pickStyle(String preset, View view) {
        if (processing || instructionCapture) {
            return;
        }
        haptic(view);
        choice.pick(preset, Prefs.expressionForPreset(this, preset));
        refreshStyleChips();
        if (recording && capturePanel) {
            // From a chip or from More: back to the panel, a style from More keeping
            // the fifth chip for the rest of this recording.
            List<String> ids = panelStyleIds();
            panelKeptStyle = RecordingChips.nextKept(ids, panelPins(ids), choice.preset, panelKeptStyle);
            if (styleSheetOpen()) {
                hideVoiceStyleOverlay();
            }
            renderRecordingPanel();
        } else {
            showVoiceStyleOverlay();
        }
        announce("Style " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression) + " for this message");
    }

    /** Make default: the only thing that saves a choice from the sheet. */
    private void makeChoiceDefault() {
        if (retoneMode) {
            return;
        }
        Prefs.setActivePreset(this, choice.preset);
        Prefs.setExpressionForPreset(this, choice.preset, choice.expression);
        choice.makeDefault();
        refreshStyleChips();
        showVoiceStyleOverlay();
        announce("Default is now " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression));
    }

    /**
     * The expression slider: five snapping stops, Reserved to Expressive, the
     * level's name beside the title. Off for Raw, which keeps the user's words as said.
     * {@code inPanel}: the recording panel's (a choice there never redraws the
     * panel mid-drag); otherwise the style sheet's.
     */
    private View expressionControl(boolean inPanel, int widthDp) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, 0);
        boolean raw = Prefs.PRESET_RAW.equals(choice.preset);
        boolean frozen = inPanel && (processing || !recording);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(sheetLabel("Expression"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView current = barText(raw ? "Off for Raw" : Prefs.expressionLabel(choice.expression), 12.5f, true,
                raw ? ui.subtle : ui.accentInk);
        titleRow.addView(current);
        panel.addView(titleRow);

        List<TextView> detentLabels = new ArrayList<>();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        String[] names = {"Reserved", "Subtle", "Natural", "Lively", "Expressive"};
        for (int i = 0; i < names.length; i++) {
            TextView label = barText(names[i], 11, false, ui.subtle);
            label.setGravity(Gravity.CENTER);
            styleExpressionDetentLabel(label, i == choice.expression);
            detentLabels.add(label);
            labels.addView(label, new LinearLayout.LayoutParams(0, dp(18), 1f));
        }

        SeekBar slider = new SeekBar(this);
        if (inPanel) {
            panelSlider = slider;
        } else {
            expressionSlider = slider;
        }
        slider.setMax(Prefs.EXPRESSION_EXPRESSIVE);
        slider.setProgress(choice.expression);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accentTintBorder));
        slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.borderStrong));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        slider.setTickMark(sliderTick());
        // Each word is centred in a fifth of the width; a tenth on each side puts
        // every stop right over its word, at any width (0.8.6; it was a fixed 26 dp).
        int side = dp(Math.max(16, widthDp / 10));
        slider.setPadding(side, 0, side, 0);
        stateDescription(slider, Prefs.expressionLabel(choice.expression));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int lastProgress = choice.expression;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && (processing || (inPanel && !recording))) {
                    // Fixed after Stop: put it back.
                    seekBar.setProgress(choice.expression);
                    return;
                }
                if (fromUser) {
                    choice.setExpression(progress);
                    refreshStyleChips();
                }
                int level = Prefs.sanitizeExpression(progress);
                current.setText(Prefs.expressionLabel(level));
                stateDescription(seekBar, Prefs.expressionLabel(level));
                for (int i = 0; i < detentLabels.size(); i++) {
                    styleExpressionDetentLabel(detentLabels.get(i), i == level);
                }
                if (fromUser && progress != lastProgress) {
                    haptic(seekBar);
                }
                lastProgress = progress;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // One message only: nothing is saved here. Make default saves it.
                refreshStyleChips();
                if (!inPanel && styleSheetOpen()) {
                    showVoiceStyleOverlay();
                }
            }
        });
        if (raw || frozen) {
            slider.setEnabled(false);
        }
        if (raw) {
            slider.setAlpha(0.4f);
            labels.setAlpha(0.4f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                slider.setStateDescription("Off for Raw");
            }
        } else if (frozen) {
            panel.setAlpha(0.5f);
        }
        panel.addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(inPanel ? 40 : 48)
        ));
        panel.addView(labels);
        return panel;
    }

    private Drawable sliderTick() {
        GradientDrawable tick = new GradientDrawable();
        tick.setShape(GradientDrawable.OVAL);
        tick.setColor(ui.borderStrong);
        tick.setSize(dp(6), dp(6));
        return tick;
    }

    private void styleExpressionDetentLabel(TextView label, boolean selected) {
        label.setTypeface(selected ? Fonts.semibold(this) : Fonts.regular(this));
        label.setTextColor(selected ? ui.accentInk : ui.subtle);
    }

    private void hideVoiceStyleOverlay() {
        if (voiceStyleOverlay == null) {
            return;
        }
        voiceStyleOverlay.animate().cancel();
        voiceStyleOverlay.setVisibility(View.GONE);
        voiceStyleOverlay.removeAllViews();
        voiceStyleOverlay.setAlpha(1f);
        voiceStyleScroller = null;
        expressionSlider = null;
        if (capturePanel && recordingPanel != null && recordingPanel.getChildCount() > 0) {
            // Back from the sheet: the panel shows what was chosen there (Codex review).
            renderRecordingPanel();
        }
        renderBar();
    }

    private void showChipStrip() {
        suggestionsShowing = true;
        if (chipScroller != null) {
            chipScroller.setVisibility(View.VISIBLE);
        }
        if (chipStrip != null) {
            chipStrip.setVisibility(View.VISIBLE);
        }
    }

    private void hideChipStrip() {
        suggestionsShowing = false;
        setPinyinCandidateStripExpanded(false);
        if (chipStrip != null) {
            chipStrip.removeAllViews();
        }
        renderBar();
    }

    private void setHistoryControlsActive(boolean active) {
        renderBar();
    }

    private TextView chip(String text, View.OnClickListener listener) {
        TextView chip = toolButton(text);
        chip.setOnClickListener(v -> {
            haptic(v);
            listener.onClick(v);
        });
        return chip;
    }

    /** A suggestion or action chip in the bar: 34 dp drawn in a 48 dp target. */
    private TextView toolButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Fonts.medium(this));
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ui.fg);
        Ui.setBackgroundKeepPadding(view, pillBackground(ui.surface, ui.borderStrong, 34, 999));
        view.setClickable(true);
        view.setMinWidth(dp(44));
        view.setMinHeight(0);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMargins(dp(2), 0, dp(2), 0);
        view.setLayoutParams(params);
        return view;
    }

    /** Makes a bar chip the tonal highlight (the best suggestion, the first candidate). */
    private void tonal(TextView chip) {
        chip.setTextColor(ui.accentInk);
        chip.setTypeface(Fonts.semibold(this));
        Ui.setBackgroundKeepPadding(chip, pillBackground(ui.accentTint, ui.accentTintBorder, 34, 999));
    }

    /** The compact Dictate button beside typing suggestions. */
    private ImageButton micButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ph_microphone);
        button.setColorFilter(ui.accentInk);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ui.accentTint);
        circle.setStroke(Math.max(1, dp(1)), ui.accentTintBorder);
        button.setBackground(new InsetDrawable(circle, dp(3)));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription("Dictate");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(4), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton plainIconButton(int drawableRes, View.OnClickListener listener) {
        ImageButton button = iconButton(drawableRes, null, listener);
        button.setColorFilter(ui.muted);
        return button;
    }

    private TextView keyButton(String text, float weight, View.OnClickListener listener) {
        return keyButton(text, weight, listener, false);
    }

    private TextView keyButton(String text, float weight, View.OnClickListener listener, boolean utility) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(text.length() > 3 ? 13 : utility ? 15 : 19);
        view.setTypeface(utility || text.length() > 3 ? Fonts.medium(this) : Fonts.regular(this));
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(colors.text);
        view.setBackground(keyVisualBackground(utility ? colors.keyAlt : colors.key, false));
        view.setClickable(true);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, 0);
        view.setOnClickListener(v -> {
            haptic(v);
            listener.onClick(v);
        });
        view.setOnTouchListener(this::handleSwipe);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, keyHeightPx(), weight);
        params.setMargins(0, 0, 0, 0);
        view.setLayoutParams(params);
        keyButtons.add(view);
        return view;
    }

    private TextView letterKeyButton(String value, float weight) {
        TextView key = keyButton(displayLetter(value), weight, v -> commitKey(value));
        key.setTag(value);
        letterButtons.add(key);
        return key;
    }

    private String displayLetter(String value) {
        return isShiftActive() ? value.toUpperCase(Locale.US) : value.toLowerCase(Locale.US);
    }

    private TextView spaceKey(float weight) {
        // A space bar reads as a space bar (0.8.8): no language name on it. The
        // language key shows which keyboard is on screen.
        TextView key = keyButton("", weight, v -> {
        });
        key.setTextSize(13);
        key.setTextColor(ui.subtle);
        key.setContentDescription("Space");
        spaceButtons.add(key);
        key.setOnTouchListener(this::handleSpaceTouch);
        return key;
    }

    private TextView deleteKey() {
        TextView key = keyButton("", 1.35f, v -> {
        }, true);
        key.setContentDescription("Delete");
        key.setBackground(iconKeyBackground(colors.keyAlt, R.drawable.ph_backspace, colors.text));
        key.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                haptic(view);
                startDeleteHold();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopDeleteHold();
                return true;
            }
            return true;
        });
        return key;
    }

    private void stylePresetChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? colors.onAccent : colors.text);
        chip.setBackground(keyBackground(selected ? colors.accent : colors.key, selected));
    }

    /**
     * A tap on Shift at {@code now} (uptime): the touch-down, so a quick double
     * tap is measured between the two presses, or a click from TalkBack.
     */
    private void toggleShift(long now) {
        if (recording || processing) {
            return;
        }
        boolean wasLocked = capsLock;
        ShiftRules.State next = ShiftRules.tap(shift, autoShift, capsLock, lastShiftTapMs, now);
        shift = next.shift;
        autoShift = next.autoShift;
        capsLock = next.capsLock;
        lastShiftTapMs = next.lastTapAt;
        updateShiftVisuals();
        if (capsLock != wasLocked) {
            capsLockTail = capsLock ? textBeforeCursorTail() : "";
            announce(capsLock ? "Caps lock on" : "Caps lock off");
        }
        setStatus(capsLock ? "Caps lock" : shift ? "Shift on" : "Ready");
    }

    /** The shift key: acts on touch-down, so two quick taps lock caps; a click (TalkBack) still works. */
    private TextView shiftKey() {
        TextView key = keyButton("", 1.35f, v -> toggleShift(android.os.SystemClock.uptimeMillis()), true);
        key.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                haptic(view);
                toggleShift(event.getEventTime());
            }
            return true;
        });
        return key;
    }

    private boolean isShiftActive() {
        return shift || autoShift || capsLock;
    }

    private void updateShiftVisuals() {
        boolean active = isShiftActive();
        for (TextView button : letterButtons) {
            Object tag = button.getTag();
            if (tag instanceof String) {
                button.setText(displayLetter((String) tag));
            }
        }
        if (shiftButton != null) {
            shiftButton.setText("");
            // Its name stays "Shift"; on and caps lock are its state, as TalkBack expects.
            shiftButton.setContentDescription("Shift");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                shiftButton.setStateDescription(capsLock ? "Caps lock" : active ? "On" : "Off");
            }
            if (capsLock) {
                shiftButton.setBackground(iconKeyBackground(colors.accent, R.drawable.ph_arrow_fat_line_up_fill, colors.onAccent));
            } else if (active) {
                shiftButton.setBackground(tonalKeyBackground(R.drawable.ph_arrow_fat_up_fill));
            } else {
                shiftButton.setBackground(iconKeyBackground(colors.keyAlt, R.drawable.ph_arrow_fat_up, colors.text));
            }
        }
    }

    private boolean handleSpaceTouch(View view, MotionEvent event) {
        if (recording || processing) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            haptic(view);
            spaceCursorMode = false;
            spaceCursorLastStepX = event.getX();
            if (spaceCursorRunnable != null) {
                mainHandler.removeCallbacks(spaceCursorRunnable);
            }
            spaceCursorRunnable = () -> {
                if (recording || processing) {
                    return;
                }
                spaceCursorMode = true;
                clearAutoCorrection();
                setStatus("Cursor");
                haptic(view);
            };
            mainHandler.postDelayed(spaceCursorRunnable, SPACE_CURSOR_HOLD_MS);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (spaceCursorMode) {
                moveCursorFromSpaceDrag(event.getX());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean wasCursorMode = spaceCursorMode;
            stopSpaceCursorTracking();
            if (!wasCursorMode) {
                if (composingPinyin()) {
                    // Standard pinyin behaviour accepts the top Hanzi candidate
                    // without a space. Literal Latin fallback behaves like
                    // English typing and keeps the separating space.
                    boolean acceptingRawLatin = pinyinSession.shouldAcceptRaw();
                    settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
                    if (acceptingRawLatin) {
                        commitText(" ");
                    }
                } else {
                    commitSeparator(" ");
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            stopSpaceCursorTracking();
            return true;
        }
        return true;
    }

    private void moveCursorFromSpaceDrag(float x) {
        int stepPx = Math.max(1, dp(SPACE_CURSOR_STEP_DP));
        int steps = (int) ((x - spaceCursorLastStepX) / stepPx);
        if (steps == 0) {
            return;
        }
        moveCursorBy(steps);
        spaceCursorLastStepX += steps * stepPx;
    }

    private void stopSpaceCursorTracking() {
        if (spaceCursorRunnable != null) {
            mainHandler.removeCallbacks(spaceCursorRunnable);
            spaceCursorRunnable = null;
        }
        spaceCursorMode = false;
    }

    private void moveCursorBy(int delta) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || delta == 0) {
            return;
        }
        if (!moveCursorWithExtractedText(connection, delta)) {
            int keyCode = delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            for (int i = 0; i < Math.abs(delta); i++) {
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            }
        }
        clearLastVoiceInsertion();
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    private boolean moveCursorWithExtractedText(InputConnection connection, int delta) {
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted == null || extracted.text == null) {
            return false;
        }
        int selection = extracted.selectionEnd >= 0 ? extracted.selectionEnd : extracted.selectionStart;
        if (selection < 0) {
            return false;
        }
        int min = extracted.startOffset;
        int max = extracted.startOffset + extracted.text.length();
        int current = Math.max(min, Math.min(max, extracted.startOffset + selection));
        int target = Math.max(min, Math.min(max, current + delta));
        if (target == current) {
            return true;
        }
        return connection.setSelection(target, target);
    }

    private void setKeyboardLocked(boolean locked) {
        if (locked && emojiPicker != null) populateKeyboardPanel(keyboardPanel);
        for (TextView button : keyButtons) {
            button.setEnabled(!locked);
        }
        if (keyboardPanel != null) {
            keyboardPanel.setAlpha(locked ? 0.38f : 1f);
        }
    }

    private void commitKey(String value) {
        clearBarMessage();
        if (recording || processing) {
            return;
        }
        clearPhraseUndo();
        retirePasteOffer();
        // A key between two Shift taps makes them two single taps, not a double tap.
        lastShiftTapMs = 0;
        boolean letterKey = !symbolsMode && value.length() == 1 && Character.isLetter(value.charAt(0));
        String text = symbolsMode
                ? value
                : (isShiftActive() ? value.toUpperCase(Locale.US) : value.toLowerCase(Locale.US));
        // In Chinese mode the letter and keypad keys feed the composer instead
        // of the editor. Pass the displayed case through so Shift/Caps can mark
        // an abbreviation as literal Latin text.
        if (!symbolsMode && handleChineseKey(text)) {
            if (letterKey && (shift || autoShift) && !capsLock) {
                shift = false;
                autoShift = false;
                lastShiftTapMs = 0;
                updateShiftVisuals();
            }
            return;
        }
        if (isSeparator(text)) {
            commitSeparator(text);
            return;
        }
        commitText(text);
        if (letterKey && (shift || autoShift) && !capsLock) {
            shift = false;
            autoShift = false;
            lastShiftTapMs = 0;
            updateShiftVisuals();
        }
        if (isAutoCorrectWordCharacter(text)) {
            scheduleAutoCorrection();
        } else {
            clearAutoCorrection();
        }
    }

    private void commitSeparator(String separator) {
        if (recording || processing) {
            return;
        }
        lastShiftTapMs = 0;
        if (inputMode.isChinese()) {
            // Take the best reading first, then emit Chinese punctuation. None of
            // the English autocorrect or phrase-replacement machinery applies.
            settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
            commitText(ChineseInputRules.fullWidthPunctuation(separator));
            showIdleChips();
            return;
        }
        if (applyTypingRule(separator)) {
            clearAutoCorrection();
            updateAutoCapitalization();
            return;
        }
        // Spelling help is deliberately suggest-only. A separator commits
        // exactly what the user typed; only tapping a chip replaces a word.
        // The phrase replacement and the separator go in one batch, so the
        // editor reports a single selection update for both.
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.beginBatchEdit();
        }
        boolean replaced = applyRecentPhraseReplacement();
        commitText(separator);
        if (connection != null) {
            connection.endBatchEdit();
        }
        if (replaced) {
            armPhraseUndo(separator);
        }
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    /**
     * Applies double-space-to-full-stop, or drops a space before closing
     * punctuation. Returns true when it handled the key.
     *
     * <p>Spelling suggestions are never applied from this path. A manually
     * chosen suggestion can only be reverted while it is still immediately in
     * front of the cursor; inserting punctuation naturally ends that window.
     */
    private boolean applyTypingRule(String separator) {
        if (!shouldTypingAssistance()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        // Three characters: the deferred colon/semicolon case looks at
        // word + space + mark.
        CharSequence before = connection.getTextBeforeCursor(3, 0);
        TypingRules.Outcome outcome = TypingRules.forSeparator(separator, before);
        if (!outcome.applies()) {
            return false;
        }
        clearLastVoiceInsertion();
        clearLastAutoCorrection();
        connection.deleteSurroundingText(outcome.deleteBefore, 0);
        connection.commitText(outcome.insert, 1);
        return true;
    }

    private void commitText(String text) {
        clearLastVoiceInsertion();
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.commitText(text, 1);
        }
    }

    private String insertVoiceText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        String prepared = prepareVoiceOutput(text);
        if (prepared.isEmpty()) {
            return "";
        }
        if (needsLeadingSpace(connection, prepared)) {
            prepared = " " + prepared;
        }
        if (!connection.commitText(prepared, 1)) {
            return "";
        }
        clearAutoCorrection();
        updateAutoCapitalization();
        return prepared;
    }

    private String appendCreatedText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted != null && extracted.text != null) {
            int end = Math.max(0, extracted.startOffset + extracted.text.length());
            if (!connection.setSelection(end, end)) {
                return "";
            }
        } else {
            CharSequence selected = connection.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                CharSequence before = connection.getTextBeforeCursor(200000, 0);
                int selectionEnd = (before == null ? 0 : before.length()) + selected.length();
                if (!connection.setSelection(selectionEnd, selectionEnd)) {
                    return "";
                }
            }
        }
        return insertVoiceText(text);
    }

    private void rememberLastVoiceInsertion(
            String rawTranscript,
            String insertedText,
            String preset,
            int expression,
            String operation,
            String targetLanguage,
            String historyId
    ) {
        if (rawTranscript == null
                || rawTranscript.trim().isEmpty()
                || insertedText == null
                || insertedText.isEmpty()
                || historyId == null
                || historyId.isEmpty()) {
            clearLastVoiceInsertion();
            return;
        }
        lastVoiceRawTranscript = rawTranscript.trim();
        lastVoiceInsertedText = insertedText;
        lastVoicePreset = preset;
        lastVoiceExpression = Prefs.sanitizeExpression(expression);
        lastVoiceOperation = VoiceHistoryItem.OPERATION_TRANSLATION.equals(operation)
                ? VoiceHistoryItem.OPERATION_TRANSLATION
                : VoiceHistoryItem.OPERATION_CREATION.equals(operation)
                        ? VoiceHistoryItem.OPERATION_CREATION
                        : VoiceHistoryItem.OPERATION_DICTATION;
        lastVoiceTargetLanguage = targetLanguage == null ? "" : targetLanguage;
        lastVoiceHistoryId = historyId;
        lastVoiceSelectionEnd = currentSelectionEnd();
        lastVoiceEditor = currentDeliveryTarget();
        lastVoiceHeldBack = "";
        lastVoiceHeldBackReason = HELD_BACK_MIXED;
        insertedFresh = true;
        mainHandler.removeCallbacks(insertedExpiry);
        mainHandler.postDelayed(insertedExpiry, VoiceBar.INSERTED_MS);
    }

    private void clearLastVoiceInsertion() {
        lastVoiceRawTranscript = "";
        lastVoiceInsertedText = "";
        lastVoicePreset = "";
        lastVoiceExpression = Prefs.DEFAULT_EXPRESSION;
        lastVoiceOperation = VoiceHistoryItem.OPERATION_DICTATION;
        lastVoiceTargetLanguage = "";
        lastVoiceHistoryId = "";
        lastVoiceSelectionEnd = -1;
        lastVoiceEditor = null;
        lastVoiceHeldBack = "";
        lastVoiceHeldBackReason = HELD_BACK_MIXED;
        insertedFresh = false;
        mainHandler.removeCallbacks(insertedExpiry);
        if (chipStrip != null && !recording && !processing && !retoneMode) {
            showIdleChips();
        }
    }

    private void openRetoneOverlay() {
        if (processing || recording || lastVoiceRawTranscript.isEmpty() || lastVoiceHistoryId.isEmpty()) {
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Retone requires a connection");
            return;
        }
        String provider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, provider)) {
            setStatus("Add a " + Prefs.providerLabel(provider) + " key to retone");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        choice.pick(lastVoicePreset, lastVoiceExpression);
        retoneMode = true;
        hideChipStrip();
        setKeyboardLocked(true);
        showVoiceStyleOverlay();
        renderBar();
    }

    private void closeRetoneOverlay(String status) {
        retoneMode = false;
        hideVoiceStyleOverlay();
        setKeyboardLocked(false);
        resetChoiceToDefault();
        showIdleChips();
        setStatus(status);
    }

    private void setRetoneTopControls(boolean active) {
        renderBar();
    }

    private void applyRetone() {
        if (!retoneMode || processing) {
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Retone requires a connection");
            return;
        }
        final String raw = lastVoiceRawTranscript;
        final String oldInserted = lastVoiceInsertedText;
        final String historyId = lastVoiceHistoryId;
        final String operation = lastVoiceOperation;
        final String targetLanguage = lastVoiceTargetLanguage;
        final String preset = choice.preset;
        final int expression = choice.expression;
        // 0.8.4: the tidy-up's word list in force now, for this field.
        final TidyWords.Block tidyWords = currentTidyWords();

        // A retone is for this text only; it never changes the saved default.
        retoneMode = false;
        processing = true;
        DictationActivity.report(true, System.currentTimeMillis());
        hideVoiceStyleOverlay();
        setKeyboardLocked(true);
        startStatusSpinner("Retoning: " + labelForPreset(preset) + " - " + Prefs.expressionLabel(expression));
        executor.execute(() -> {
            try {
                String result;
                if (VoiceHistoryItem.OPERATION_TRANSLATION.equals(operation)) {
                    result = TransformClient.translate(this, raw, targetLanguage, preset, expression);
                } else if (VoiceHistoryItem.OPERATION_CREATION.equals(operation)) {
                    result = TransformClient.createText(this, raw, preset, expression);
                } else {
                    result = TransformClient.transformChecked(this, raw, preset, expression, tidyWords).text;
                }
                if (result == null || result.trim().isEmpty()) {
                    throw new IllegalStateException("Retone returned no text.");
                }
                String finished = VoiceHistoryItem.OPERATION_DICTATION.equals(operation)
                        ? finishDictatedText(result, Prefs.allPhraseReplacements(this))
                        : result;
                mainHandler.post(() -> {
                    Prefs.updateTranscriptHistory(this, historyId, raw, finished, preset, expression);
                    String replacement = replaceLastVoiceInsertion(oldInserted, finished);
                    if (!replacement.isEmpty()) {
                        rememberLastVoiceInsertion(
                                raw,
                                replacement,
                                preset,
                                expression,
                                operation,
                                targetLanguage,
                                historyId
                        );
                        finishProcessingState("Retoned - " + labelForPreset(preset)
                                + " - " + Prefs.expressionLabel(expression));
                    } else {
                        clearLastVoiceInsertion();
                        finishProcessingState("Retone saved in history; field changed");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState("Retone failed: " + concise(e)));
            }
        });
    }

    private String replaceLastVoiceInsertion(String oldInserted, String newText) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || oldInserted == null || oldInserted.isEmpty()) {
            return "";
        }
        int cursor = collapsedCursor(connection);
        DeliveryGuard.Verdict verdict = DeliveryGuard.mayReplace(
                new DeliveryGuard.Insertion(lastVoiceEditor, lastVoiceSelectionEnd, oldInserted),
                currentDeliveryTarget(),
                cursor >= 0 ? 0 : -1,
                cursor,
                connection.getTextBeforeCursor(oldInserted.length(), 0)
        );
        if (verdict != DeliveryGuard.Verdict.OK) {
            return "";
        }
        String prepared = prepareVoiceOutput(newText);
        if (prepared.isEmpty()) {
            return "";
        }
        if (oldInserted.startsWith(" ") && !prepared.startsWith(" ")) {
            prepared = " " + prepared;
        }
        connection.beginBatchEdit();
        try {
            if (!connection.deleteSurroundingText(oldInserted.length(), 0)) {
                return "";
            }
            return connection.commitText(prepared, 1) ? prepared : "";
        } finally {
            connection.endBatchEdit();
            clearAutoCorrection();
            updateAutoCapitalization();
        }
    }

    /**
     * The cursor, only when the editor reports both ends of the selection and
     * they are the same place; otherwise -1. A null from getSelectedText can mean
     * "no selection" or "could not tell", so it is never taken as proof of either.
     */
    private static int collapsedCursor(InputConnection connection) {
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0
                || extracted.selectionStart != extracted.selectionEnd) {
            return -1;
        }
        return extracted.startOffset + extracted.selectionEnd;
    }

    private int currentSelectionEnd() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return -1;
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        // selectionEnd is relative to the extracted slice; onUpdateSelection
        // reports absolute positions, so compare like with like.
        return extracted == null || extracted.selectionEnd < 0
                ? -1
                : extracted.startOffset + extracted.selectionEnd;
    }

    /**
     * Replaces the whole field, but only if, once everything is selected, the
     * editor reports exactly {@code expectedSource} as the selection. Anything else
     * (a field that changed, or one that will not say) puts the cursor back and
     * leaves the field alone.
     */
    private boolean replaceWholeFieldText(String text, String expectedSource) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || text == null || text.trim().isEmpty() || expectedSource == null) {
            return false;
        }
        int restoreAt = currentSelectionEnd();
        connection.beginBatchEdit();
        try {
            boolean selectedAll = connection.performContextMenuAction(android.R.id.selectAll);
            if (!selectedAll) {
                ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
                if (extracted == null || extracted.text == null) {
                    return false;
                }
                int start = Math.max(0, extracted.startOffset);
                if (!connection.setSelection(start, start + extracted.text.length())) {
                    return false;
                }
            }
            CharSequence selectedNow = connection.getSelectedText(0);
            if (selectedNow == null || !expectedSource.contentEquals(selectedNow)) {
                if (restoreAt >= 0) {
                    connection.setSelection(restoreAt, restoreAt);
                }
                return false;
            }
            boolean replaced = connection.commitText(text, 1);
            if (replaced) {
                clearLastVoiceInsertion();
                clearAutoCorrection();
                updateAutoCapitalization();
            }
            return replaced;
        } finally {
            connection.endBatchEdit();
        }
    }

    private void pasteHistoryText(String text) {
        if (text == null || text.trim().isEmpty()) {
            setStatus("Nothing to paste");
            return;
        }
        clearLastVoiceInsertion();
        hideHistoryPanel();
        insertVoiceText(text);
        setStatus("Pasted");
    }

    private void copyHistoryText(String text) {
        if (text == null || text.trim().isEmpty()) {
            setStatus("Nothing to copy");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            setStatus("Clipboard unavailable");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("VoiceFlow transcript", text.trim()));
        setStatus("Copied");
    }

    private void createHistoryTransform(VoiceHistoryItem item, String preset, int expression) {
        if (item.rawText.trim().isEmpty()) {
            setStatus("No raw transcript saved");
            return;
        }
        if (Prefs.PRESET_RAW.equals(preset)) {
            Prefs.selectTranscriptHistoryVariant(this, item.id, Prefs.PRESET_RAW, Prefs.DEFAULT_EXPRESSION);
            showHistoryPanel();
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("No connection for transform");
            return;
        }
        processing = true;
        setKeyboardLocked(true);
        String styleLabel = historyOutputLabel(preset, expression);
        startStatusSpinner(item.isTranslation()
                ? "Translating: " + styleLabel
                : "Creating " + styleLabel);
        // 0.8.4: the tidy-up's word list in force now, for this field.
        final TidyWords.Block tidyWords = currentTidyWords();
        executor.execute(() -> {
            try {
                String result;
                if (item.isTranslation()) {
                    result = TransformClient.translate(this, item.rawText, item.targetLanguage, preset, expression);
                } else if (item.isCreation()) {
                    result = TransformClient.createText(this, item.rawText, preset, expression);
                } else {
                    result = finishDictatedText(
                            TransformClient.transformChecked(this, item.rawText, preset, expression, tidyWords).text,
                            Prefs.allPhraseReplacements(this)
                    );
                }
                String stored = result;
                mainHandler.post(() -> {
                    Prefs.updateTranscriptHistory(this, item.id, item.rawText, stored, preset, expression);
                    stopStatusSpinner();
                    recording = false;
                    processing = false;
                    setKeyboardLocked(false);
                    setHistoryControlsActive(true);
                    showHistoryPanel();
                    setStatus((item.isTranslation() ? "Translated - " : "Created ") + styleLabel);
                });
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState("Create failed: " + concise(e)));
            }
        });
    }

    private String prepareVoiceOutput(String text) {
        // Corrections are applied once, by whoever produced the text; doing it again
        // here would chain one correction into another.
        String result = text == null ? "" : text.trim();
        result = DictationText.normalizeMixedScript(result);
        result = removeShortTrailingPeriod(result);
        return result;
    }

    private String applyPhraseReplacements(String text) {
        return PersonalVocabulary.applyReplacements(text, Prefs.allPhraseReplacements(this));
    }

    private String removeShortTrailingPeriod(String text) {
        if (text.indexOf('\n') >= 0 || !text.endsWith(".") || text.endsWith("...")) {
            return text;
        }
        String withoutPeriod = text.substring(0, text.length() - 1).trim();
        int words = countWords(withoutPeriod);
        if (words > 0 && words < 5) {
            return withoutPeriod;
        }
        return text;
    }

    private int countWords(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean word = Character.isLetterOrDigit(text.charAt(i));
            if (word && !inWord) {
                count++;
            }
            inWord = word;
        }
        return count;
    }

    private boolean needsLeadingSpace(InputConnection connection, String text) {
        if (text.isEmpty() || startsWithSpacingOrPunctuation(text)) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(1, 0);
        if (before == null || before.length() == 0) {
            return false;
        }
        char previous = before.charAt(before.length() - 1);
        return !Character.isWhitespace(previous) && "([{/'\"".indexOf(previous) < 0;
    }

    private boolean startsWithSpacingOrPunctuation(String text) {
        char first = text.charAt(0);
        return Character.isWhitespace(first) || ".,?!:;)]}/'\"".indexOf(first) >= 0;
    }

    private void deleteOne() {
        clearBarMessage();
        if (recording || processing) {
            return;
        }
        lastShiftTapMs = 0;
        // While composing, backspace shortens the pinyin buffer rather than
        // deleting text the user has already committed.
        if (composingPinyin()) {
            pinyinSession.backspace();
            if (composingPinyin()) {
                refreshComposingText();
            } else {
                // The buffer is empty, so settle() would report NOTHING and the
                // editor would keep an orphaned composing span. Clear it here.
                clearComposingRegion();
                showIdleChips();
            }
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            clearLastVoiceInsertion();
            if (deleteSelectionIfAny(connection)) {
                clearPhraseUndo();
                scheduleAutoCorrection();
                updateAutoCapitalization();
                return;
            }
            if (undoPhraseReplacementIfPossible(connection)) {
                updateAutoCapitalization();
                return;
            }
            if (undoLastAutoCorrectionIfPossible(connection)) {
                updateAutoCapitalization();
                return;
            }
            clearLastAutoCorrection();
            CharSequence before = connection.getTextBeforeCursor(128, 0);
            int length = emojiCatalog != null ? emojiCatalog.deletionLength(before)
                    : before == null || before.length() == 0 ? 1
                    : Character.charCount(Character.codePointBefore(before, before.length()));
            if (length > 0) connection.deleteSurroundingText(length, 0);
        }
        scheduleAutoCorrection();
        updateAutoCapitalization();
    }

    private void deletePreviousWord() {
        if (recording || processing) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        clearLastVoiceInsertion();
        if (deleteSelectionIfAny(connection)) {
            scheduleAutoCorrection();
            updateAutoCapitalization();
            return;
        }
        CharSequence before = connection.getTextBeforeCursor(80, 0);
        if (before == null || before.length() == 0) {
            return;
        }
        int i = before.length() - 1;
        int count = 0;
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
            i--;
            count++;
        }
        while (i >= 0 && !Character.isWhitespace(before.charAt(i))) {
            i--;
            count++;
        }
        clearLastAutoCorrection();
        connection.deleteSurroundingText(Math.max(count, 1), 0);
        updateAutoCapitalization();
    }

    private boolean deleteSelectionIfAny(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);
        if (selected == null || selected.length() == 0) {
            return false;
        }
        clearLastAutoCorrection();
        clearAutoCorrection();
        connection.commitText("", 1);
        return true;
    }

    private void startDeleteHold() {
        if (recording || processing) {
            return;
        }
        deleteHeld = true;
        deleteHoldStartMs = System.currentTimeMillis();
        deleteOne();
        if (deleteRepeatRunnable == null) {
            deleteRepeatRunnable = () -> {
                if (!deleteHeld) {
                    return;
                }
                long heldMs = System.currentTimeMillis() - deleteHoldStartMs;
                if (heldMs >= DELETE_HOLD_CLEAR_MS) {
                    // Keep going only while something was deleted: an editor
                    // may hand back less than all of its text in one read.
                    if (deleteAllBeforeCursor()) {
                        mainHandler.postDelayed(deleteRepeatRunnable, 320);
                    }
                } else if (heldMs >= DELETE_HOLD_WORDS_MS) {
                    deletePreviousWord();
                    mainHandler.postDelayed(deleteRepeatRunnable, 320);
                } else {
                    deleteOne();
                    mainHandler.postDelayed(deleteRepeatRunnable, 75);
                }
            };
        }
        mainHandler.postDelayed(deleteRepeatRunnable, 350);
    }

    /**
     * The end of a long delete hold: removes everything before the cursor in
     * one edit. Text after the cursor is left alone, as backspace never deletes
     * forward. Returns true when it deleted anything.
     */
    private boolean deleteAllBeforeCursor() {
        if (recording || processing) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(200000, 0);
        if (before == null || before.length() == 0) {
            return false;
        }
        clearLastVoiceInsertion();
        clearLastAutoCorrection();
        clearPhraseUndo();
        if (!connection.deleteSurroundingText(before.length(), 0)) {
            return false;
        }
        clearAutoCorrection();
        updateAutoCapitalization();
        setStatus("Cleared");
        return true;
    }

    private void stopDeleteHold() {
        deleteHeld = false;
        if (deleteRepeatRunnable != null) {
            mainHandler.removeCallbacks(deleteRepeatRunnable);
        }
    }

    private void scheduleAutoCorrection() {
        if (!shouldAutoCorrectTyping()) {
            clearAutoCorrection();
            return;
        }
        if (correctionRunnable == null) {
            correctionRunnable = this::requestAutoCorrectionForCurrentWord;
        }
        mainHandler.removeCallbacks(correctionRunnable);
        mainHandler.postDelayed(correctionRunnable, CORRECTION_DELAY_MS);
    }

    private void requestAutoCorrectionForCurrentWord() {
        if (!shouldAutoCorrectTyping()) {
            clearAutoCorrection();
            return;
        }
        String word = currentWordBeforeCursor();
        if (word.length() < 2 || containsDigit(word)) {
            clearAutoCorrection();
            return;
        }
        if (Prefs.isLearnedWord(this, word)) {
            clearAutoCorrection();
            return;
        }

        // The explicit table still outranks the lexicon. It is the only thing
        // that can fix a first-letter transposition ("hte") or expand a
        // contraction the lexicon has no typo for ("dont"), because correction
        // proper never changes the first letter.
        String fallback = fallbackCorrectionFor(word);
        if (!fallback.isEmpty()) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add(fallback);
            setPendingAutoCorrection(word, suggestions);
            return;
        }

        EnglishEngine engine = englishEngine();
        engine.prepare();
        if (!engine.isReady()) {
            // Still parsing, or the asset is unreadable. Say nothing rather
            // than guessing from a half-built lexicon.
            clearAutoCorrection();
            return;
        }
        engine.suggest(word, 3, ++correctionGeneration);
    }

    private EnglishEngine englishEngine() {
        if (englishEngine == null) {
            englishEngine = new EnglishEngine(
                    getAssets(), typingExecutor, mainHandler, this::applyEnglishSuggestions);
        }
        return englishEngine;
    }

    /**
     * Publishes a finished background pass, if it is still wanted.
     *
     * <p>Everything the request was predicated on is rechecked here, because
     * arbitrarily much can happen between dispatch and delivery: the user keeps
     * typing, switches field, switches to Chinese, or starts recording. The
     * generation check alone is not enough — {@link #clearAutoCorrection()} also
     * bumps it, so a stale result is dropped even when no newer request exists.
     */
    private void applyEnglishSuggestions(EnglishEngine.Suggestions suggestions) {
        if (suggestions.generation != correctionGeneration || !shouldAutoCorrectTyping()) {
            return;
        }
        String word = currentWordBeforeCursor();
        if (!suggestions.word.equals(word)) {
            // The word moved on under us; a newer request is already in flight.
            return;
        }
        if (Prefs.isLearnedWord(this, word)) {
            clearAutoCorrection();
            return;
        }
        if (suggestions.correction.isEmpty()) {
            setPendingAutoComplete(word, suggestions.completions);
            return;
        }
        List<String> cased = new ArrayList<>();
        for (String candidate : suggestions.correction.words) {
            addSuggestion(cased, matchCase(word, candidate));
        }
        if (cased.isEmpty()) {
            setPendingAutoComplete(word, suggestions.completions);
            return;
        }
        setPendingAutoCorrection(word, cased);
    }

    private boolean applySpellingSuggestion() {
        return applySpellingSuggestion(pendingAutoCorrectReplacement);
    }

    private boolean applySpellingSuggestion(String replacement) {
        if (replacement == null || replacement.trim().isEmpty()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        String word = currentWordBeforeCursor();
        if (!word.equals(pendingAutoCorrectWord)) {
            clearAutoCorrection();
            return false;
        }
        connection.deleteSurroundingText(word.length(), 0);
        connection.commitText(replacement, 1);
        rememberLastAutoCorrection(word, replacement);
        clearAutoCorrection();
        return true;
    }

    private void rejectPendingAutoCorrection() {
        if (!pendingAutoCorrectWord.isEmpty()) {
            learnAutoCorrectionWord(pendingAutoCorrectWord);
            setStatus("Kept " + pendingAutoCorrectWord);
        }
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    private String alternateAutoCorrection() {
        for (String suggestion : pendingAutoCorrectSuggestions) {
            if (!suggestion.equals(pendingAutoCorrectReplacement)) {
                return suggestion;
            }
        }
        return "";
    }

    private void setPendingAutoCorrection(String word, List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            clearAutoCorrection();
            return;
        }
        pendingAutoCorrectWord = word;
        pendingAutoCompletePrefix = "";
        pendingAutoCompleteSuggestions.clear();
        pendingAutoCorrectSuggestions.clear();
        for (String suggestion : suggestions) {
            addSuggestion(pendingAutoCorrectSuggestions, suggestion);
        }
        if (pendingAutoCorrectSuggestions.isEmpty()) {
            clearAutoCorrection();
            return;
        }
        pendingAutoCorrectReplacement = pendingAutoCorrectSuggestions.get(0);
        // There is intentionally no auto-accept flag. The scorer may rank a
        // candidate, but replacing typed text always requires a chip tap.
        showIdleChips();
    }

    private void setPendingAutoComplete(String prefix, List<String> completions) {
        pendingAutoCorrectWord = "";
        pendingAutoCorrectReplacement = "";
        pendingAutoCorrectSuggestions.clear();
        pendingAutoCompletePrefix = prefix == null ? "" : prefix;
        pendingAutoCompleteSuggestions.clear();
        if (pendingAutoCompletePrefix.length() < 2 || containsDigit(pendingAutoCompletePrefix)) {
            hideChipStrip();
            return;
        }
        if (completions != null) {
            for (String completion : completions) {
                addCompletion(
                        pendingAutoCompleteSuggestions,
                        matchCase(pendingAutoCompletePrefix, completion));
                if (pendingAutoCompleteSuggestions.size() >= 3) {
                    break;
                }
            }
        }
        showIdleChips();
    }

    private void applyAutoCompleteSuggestion(String suggestion) {
        if (suggestion == null || suggestion.trim().isEmpty()) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        String word = currentWordBeforeCursor();
        if (!word.equals(pendingAutoCompletePrefix)) {
            clearAutoCorrection();
            return;
        }
        connection.deleteSurroundingText(word.length(), 0);
        connection.commitText(suggestion.trim() + " ", 1);
        clearLastAutoCorrection();
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    private void clearAutoCorrection() {
        pendingAutoCorrectWord = "";
        pendingAutoCorrectReplacement = "";
        pendingAutoCorrectSuggestions.clear();
        pendingAutoCompletePrefix = "";
        pendingAutoCompleteSuggestions.clear();
        // Invalidate anything already running on the typing executor. Removing
        // the debounce callback only stops work that has not started; a pass
        // already in flight would otherwise come back and repopulate the chips
        // we are clearing here.
        correctionGeneration++;
        if (correctionRunnable != null) {
            mainHandler.removeCallbacks(correctionRunnable);
        }
        // Clearing suggestions must not take down a Paste offer that still
        // stands, such as the one a password field keeps while typing.
        if (chipStrip != null && !recording && !processing && !showPasteChip()) {
            hideChipStrip();
        }
    }

    private void addSuggestion(List<String> suggestions, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : suggestions) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        if (suggestions.size() < 2) {
            suggestions.add(trimmed);
        }
    }

    private void addCompletion(List<String> suggestions, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : suggestions) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        suggestions.add(trimmed);
    }

    private void rememberLastAutoCorrection(String original, String replacement) {
        lastAutoCorrectOriginal = original == null ? "" : original;
        lastAutoCorrectReplacement = replacement == null ? "" : replacement;
    }

    private void clearLastAutoCorrection() {
        lastAutoCorrectOriginal = "";
        lastAutoCorrectReplacement = "";
    }

    private boolean undoLastAutoCorrectionIfPossible(InputConnection connection) {
        if (lastAutoCorrectOriginal.isEmpty() || lastAutoCorrectReplacement.isEmpty()) {
            return false;
        }
        String tail = lastAutoCorrectReplacement;
        if (tail.isEmpty()) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(tail.length(), 0);
        if (before == null || !tail.contentEquals(before)) {
            clearLastAutoCorrection();
            return false;
        }
        connection.deleteSurroundingText(tail.length(), 0);
        connection.commitText(lastAutoCorrectOriginal, 1);
        learnAutoCorrectionWord(lastAutoCorrectOriginal);
        clearLastAutoCorrection();
        clearAutoCorrection();
        setStatus("Reverted");
        return true;
    }

    private void learnAutoCorrectionWord(String word) {
        if (!canRememberFromThisField() || !shouldLearnAutoCorrectionWord(word)) {
            return;
        }
        Prefs.learnWord(this, word);
    }

    private boolean shouldLearnAutoCorrectionWord(String word) {
        if (word == null || COMMON_TYPOS.containsKey(word.toLowerCase(Locale.US)) || containsDigit(word)) {
            return false;
        }
        String trimmed = word.trim();
        if (trimmed.length() < 2 || trimmed.length() > 40) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!isAutoCorrectWordCharacter(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void updateAutoCapitalization() {
        if (capsLock || shift || symbolsMode || recording || processing) {
            if (autoShift) {
                autoShift = false;
                updateShiftVisuals();
            }
            return;
        }
        boolean next = shouldAutoCapitalize();
        if (autoShift != next) {
            autoShift = next;
            updateShiftVisuals();
        }
    }

    private boolean shouldAutoCapitalize() {
        if (!shouldTypingAssistance()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(80, 0);
        if (before == null || before.length() == 0) {
            return true;
        }
        for (int i = before.length() - 1; i >= 0; i--) {
            char value = before.charAt(i);
            if (value == '\n') {
                return true;
            }
            if (Character.isWhitespace(value)) {
                continue;
            }
            return value == '.' || value == '?' || value == '!';
        }
        return true;
    }

    private boolean shouldTypingAssistance() {
        if (emojiPicker != null) return false;
        // English spelling help has nothing useful to say about pinyin, and
        // auto-capitalisation would fight the composer. Off for the whole of
        // Chinese mode, not just while a buffer is active.
        if (inputMode.isChinese()) {
            return false;
        }
        return isAssistableField();
    }

    /**
     * False for passwords, emails, URLs, non-text fields and fields that ask for
     * no suggestions: anything where rewriting what was typed would be wrong.
     */
    private boolean isAssistableField() {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) {
            return true;
        }
        int inputType = info.inputType;
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        if ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
            return false;
        }
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return !isTypingAssistanceBlockedVariation(variation);
    }

    private boolean shouldAutoCorrectTyping() {
        if (recording || processing || symbolsMode) {
            return false;
        }
        return shouldTypingAssistance();
    }

    private boolean shouldAllowVoiceCapture() {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) {
            return true;
        }
        int inputType = info.inputType;
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return !isPasswordVariation(variation);
    }

    private boolean isTypingAssistanceBlockedVariation(int variation) {
        return isPasswordVariation(variation)
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
    }

    private boolean isPasswordVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    private String currentWordBeforeCursor() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        CharSequence before = connection.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) {
            return "";
        }
        int end = before.length();
        int start = end;
        while (start > 0 && isAutoCorrectWordCharacter(before.charAt(start - 1))) {
            start--;
        }
        return before.subSequence(start, end).toString();
    }

    private boolean isAutoCorrectWordCharacter(String text) {
        return text.length() == 1 && isAutoCorrectWordCharacter(text.charAt(0));
    }

    private boolean isAutoCorrectWordCharacter(char value) {
        return Character.isLetter(value) || value == '\'';
    }

    private boolean isSeparator(String text) {
        return " ".equals(text)
                || ".".equals(text)
                || ",".equals(text)
                || "?".equals(text)
                || "!".equals(text)
                || ":".equals(text)
                || ";".equals(text);
    }

    private boolean containsDigit(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isDigit(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String fallbackCorrectionFor(String word) {
        String replacement = COMMON_TYPOS.get(word.toLowerCase(Locale.US));
        return replacement == null ? "" : matchCase(word, replacement);
    }

    private String matchCase(String original, String replacement) {
        if (original.equals(original.toUpperCase(Locale.US))) {
            return replacement.toUpperCase(Locale.US);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return replacement.substring(0, 1).toUpperCase(Locale.US) + replacement.substring(1);
        }
        return replacement;
    }

    /**
     * Rewrites a phrase replacement that ends right before the cursor. Returns
     * true when it replaced something, leaving what was typed in
     * {@link #phraseUndoTyped} so {@link #armPhraseUndo} can offer it back.
     *
     * <p>Never runs in a password, email, URL or no-suggestions field: a
     * replacement there silently changes a secret or an address.
     */
    private boolean applyRecentPhraseReplacement() {
        clearPhraseUndo();
        if (!isAssistableField()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(96, 0);
        if (before == null || before.length() == 0) {
            return false;
        }
        String text = before.toString();
        PersonalVocabulary.ReplacementMatch match = PersonalVocabulary.findReplacementAtEnd(
                text,
                Prefs.allPhraseReplacements(this)
        );
        if (match == null) {
            return false;
        }
        phraseUndoTyped = text.substring(text.length() - match.matchedLength);
        phraseUndoReplaced = match.replacement;
        connection.deleteSurroundingText(match.matchedLength, 0);
        connection.commitText(match.replacement, 1);
        return true;
    }

    /**
     * Makes the replacement just applied undoable by the very next backspace.
     * The cursor is recorded so a tap elsewhere disarms the undo. An editor
     * that cannot report its cursor gets no undo: matching the text alone could
     * rewrite an identical phrase somewhere else.
     */
    private void armPhraseUndo(String separator) {
        phraseUndoTyped += separator;
        phraseUndoReplaced += separator;
        phraseUndoCursor = currentSelectionEnd();
    }

    private void clearPhraseUndo() {
        phraseUndoTyped = "";
        phraseUndoReplaced = "";
        phraseUndoCursor = -1;
    }

    /**
     * A backspace straight after a phrase replacement puts back exactly what was
     * typed, separator included. Keeping the separator matters: the phrase then
     * no longer ends the text, so the next separator cannot re-apply it.
     */
    private boolean undoPhraseReplacementIfPossible(InputConnection connection) {
        String typed = phraseUndoTyped;
        String replaced = phraseUndoReplaced;
        int cursor = phraseUndoCursor;
        clearPhraseUndo();
        if (replaced.isEmpty() || cursor < 0 || currentSelectionEnd() != cursor) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(replaced.length(), 0);
        if (before == null || !replaced.contentEquals(before)) {
            return false;
        }
        connection.beginBatchEdit();
        connection.deleteSurroundingText(replaced.length(), 0);
        connection.commitText(typed, 1);
        connection.endBatchEdit();
        setStatus("Reverted");
        return true;
    }

    private void sendEnter() {
        if (recording || processing) {
            return;
        }
        // Any key between two Shift taps makes them two single taps.
        lastShiftTapMs = 0;
        // A half-typed reading must be resolved before the editor action fires,
        // or the composing span can be submitted as raw latin.
        if (composingPinyin()) {
            settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        clearLastVoiceInsertion();
        applyRecentPhraseReplacement();
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (connection.performEditorAction(action)) {
                return;
            }
        }
        boolean multiline = info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (multiline) {
            connection.commitText("\n", 1);
            clearAutoCorrection();
            updateAutoCapitalization();
            return;
        }
        clearAutoCorrection();
        updateAutoCapitalization();
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    // ------------------------------------------------------------- Responsive

    /**
     * Screen-derived geometry for one build of the keyboard.
     *
     * <p>Recomputed in {@link #onCreateInputView()}, which the framework calls
     * again after a configuration change: InputMethodService.onConfigurationChanged
     * runs resetStateForNewConfiguration -> initViews, and initViews nulls the
     * cached input view. That is also why the IME service must NOT declare
     * android:configChanges — doing so marks those diffs handled and suppresses
     * the rebuild.
     */
    private static final class KeyboardMetrics {
        final int keyHeightDp;
        final boolean split;
        final int gutterDp;
        final int outerInsetDp;

        private KeyboardMetrics(int keyHeightDp, boolean split, int gutterDp, int outerInsetDp) {
            this.keyHeightDp = keyHeightDp;
            this.split = split;
            this.gutterDp = gutterDp;
            this.outerInsetDp = outerInsetDp;
        }

        static KeyboardMetrics from(Configuration configuration, boolean splitEnabled) {
            boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
            // Key height follows the screen; whether the rows split also
            // follows the Settings switch.
            boolean large = KeyboardGeometry.shouldSplit(configuration.smallestScreenWidthDp);
            boolean split = KeyboardGeometry.shouldSplit(configuration.smallestScreenWidthDp, splitEnabled);
            return new KeyboardMetrics(
                    KeyboardGeometry.keyHeightDp(landscape, large),
                    split,
                    split ? KeyboardGeometry.gutterDp(configuration.screenWidthDp) : 0,
                    KeyboardGeometry.outerInsetDp(split)
            );
        }
    }

    private KeyboardMetrics metrics =
            new KeyboardMetrics(KeyboardGeometry.KEY_HEIGHT_DP, false, 0, 0);

    private int keyHeightPx() {
        return dp(metrics.keyHeightDp);
    }

    private int splitIndexFor(KeyboardGeometry.Row row, int keyCount) {
        return KeyboardGeometry.splitIndex(row, keyCount, metrics.split);
    }

    /** The gap between the two halves. */
    private View splitSpacer() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(metrics.gutterDp), keyHeightPx()));
        return spacer;
    }

    // ---------------------------------------------------------------- Chinese

    private boolean chineseAvailable() {
        return Prefs.chineseInputEnabled(this);
    }

    private boolean composingPinyin() {
        return pinyinSession != null && pinyinSession.isComposing();
    }

    private PinyinEngine pinyinEngine() {
        if (pinyinEngine == null) {
            // 0.9.0: Rime, with Classic behind it, unless she chose Classic in Settings.
            pinyinEngine = new PinyinEngine(getAssets(), executor, mainHandler, this::onPinyinEngineReady,
                    RimeAndroid.engine(this),
                    () -> Prefs.CHINESE_ENGINE_RIME.equals(Prefs.chineseEngine(this)));
        }
        return pinyinEngine;
    }

    private void onPinyinEngineReady() {
        if (!inputMode.isChinese()) {
            return;
        }
        PinyinConverter composer = pinyinEngine().composerFor(inputMode);
        if (composer == null) {
            if (pinyinEngine().hasFailed()) {
                setStatus("Chinese unavailable");
            }
            return;
        }
        if (pinyinSession == null) {
            pinyinSession = new PinyinSession(composer);
        } else {
            pinyinSession.setComposer(composer);
        }
        setStatus(inputMode.isKeypad() ? "中文 九宫格" : "中文 全键");
        showIdleChips();
    }

    /**
     * Applies a settlement to the editor. Every path out of a composition goes
     * through here so composing text can never be left behind.
     */
    private void applySettlement(PinyinSession.Settlement settlement) {
        if (settlement == null || settlement.isNothing()) {
            return;
        }
        if (settlement.action == PinyinSession.Action.CLEAR_STATE_ONLY) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        switch (settlement.action) {
            case COMMIT:
                connection.commitText(settlement.text, 1);
                break;
            case KEEP_RAW:
                connection.finishComposingText();
                break;
            case DISCARD:
                connection.setComposingText("", 1);
                connection.finishComposingText();
                break;
            default:
                break;
        }
    }

    /** Ends any composition in progress. Safe to call when nothing is composing. */
    private void settlePinyin(PinyinSession.SettleReason reason) {
        if (pinyinSession == null) {
            return;
        }
        applySettlement(pinyinSession.settle(reason));
        refreshComposingText();
    }

    /** Mirrors the raw buffer into the editor as underlined composing text. */
    private void refreshComposingText() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        if (composingPinyin()) {
            // On the 9-key pad the field shows the reading ("ni'hao"), not digits.
            connection.setComposingText(pinyinSession.composingText(), 1);
        }
        showIdleChips();
    }

    /** Removes any composing span from the editor without committing it. */
    private void clearComposingRegion() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        connection.setComposingText("", 1);
        connection.finishComposingText();
    }

    /**
     * Routes a typed character into the pinyin buffer.
     *
     * @return true when Chinese input consumed the key
     */
    private boolean handleChineseKey(String value) {
        if (!inputMode.isChinese() || value.length() != 1) {
            return false;
        }
        // Only take characters this layout can actually spell with. Anything
        // else (punctuation reaching commitKey, say) falls through to the normal
        // path rather than being swallowed into the buffer.
        char typed = value.charAt(0);
        boolean usable = inputMode.isKeypad()
                ? (typed >= '2' && typed <= '9')
                : Character.isLetter(typed) && typed < 128;
        if (!usable) {
            return false;
        }
        if (pinyinSession == null) {
            // Engine still loading. Swallow the keystroke rather than emitting
            // raw latin into a Chinese message.
            pinyinEngine().prepare(inputMode);
            setStatus("Loading Chinese…");
            return true;
        }
        if (!pinyinSession.append(value.charAt(0))) {
            return true;
        }
        refreshComposingText();
        return true;
    }

    private void selectPinyinCandidate(int index) {
        if (pinyinSession == null) {
            return;
        }
        applySettlement(pinyinSession.select(index));
        if (composingPinyin()) {
            refreshComposingText();
        } else {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) {
                connection.finishComposingText();
            }
            showIdleChips();
        }
    }

    /** Chooses the literal Latin candidate shown before the Hanzi candidates. */
    private void selectRawPinyin() {
        if (pinyinSession == null) {
            return;
        }
        applySettlement(pinyinSession.selectRaw());
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.finishComposingText();
        }
        showIdleChips();
    }

    /** Candidate strip. Highest priority: it owns the strip while composing. */
    private boolean showPinyinCandidates() {
        if (chipStrip == null || !composingPinyin() || recording || processing || historyMode) {
            setPinyinCandidateStripExpanded(false);
            if (pinyinSpellingText != null) {
                pinyinSpellingText.setVisibility(View.GONE);
            }
            return false;
        }
        List<PinyinCandidate> candidates = pinyinSession.candidates();
        boolean keypad = pinyinSession.isKeypad();
        setPinyinCandidateStripExpanded(true);
        chipStrip.removeAllViews();
        chipScroller.scrollTo(0, 0);
        showChipStrip();
        if (pinyinSpellingText != null) {
            pinyinSpellingText.setVisibility(keypad ? View.VISIBLE : View.GONE);
            pinyinSpellingText.setText(keypad ? pinyinSession.spellingDisplay() : "");
        }
        int chipHeightDp = keypad ? BAR_HEIGHT_DP - PINYIN_SPELLING_LINE_DP - 2 : 48;
        if (!keypad) {
            TextView raw = pinyinCandidateChip(pinyinSession.rawText(), v -> selectRawPinyin(), chipHeightDp);
            raw.setContentDescription("Type " + pinyinSession.rawText() + " as Latin text");
            raw.setTextColor(ui.muted);
            if (candidates.isEmpty()) {
                tonal(raw);
            }
            chipStrip.addView(raw);
        }
        for (int i = 0; i < candidates.size(); i++) {
            final int index = i;
            TextView candidate = pinyinCandidateChip(
                    candidates.get(i).text,
                    v -> selectPinyinCandidate(index),
                    chipHeightDp);
            if (i == 0) {
                tonal(candidate);
            }
            chipStrip.addView(candidate);
        }
        if (keypad) {
            // Last, the letters themselves, for a word that is not Chinese.
            String letters = pinyinSession.composingText().replace("'", "");
            TextView latin = pinyinCandidateChip(letters, v -> selectSpelledLetters(), chipHeightDp);
            latin.setContentDescription("Type " + letters + " as Latin text");
            latin.setTextColor(ui.muted);
            if (candidates.isEmpty()) {
                tonal(latin);
            }
            chipStrip.addView(latin);
        }
        return true;
    }

    /** Height of the 9-key spelling line over the candidates. */
    private static final int PINYIN_SPELLING_LINE_DP = 16;

    /** 9-key: commits the spelled letters as Latin text. */
    private void selectSpelledLetters() {
        if (pinyinSession == null) {
            return;
        }
        applySettlement(pinyinSession.selectSpelledLetters());
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.finishComposingText();
        }
        showIdleChips();
    }

    /**
     * Gives pinyin candidates the complete toolbar width while composing. The
     * action buttons return as soon as the composition is committed or cleared.
     */
    private void setPinyinCandidateStripExpanded(boolean expanded) {
        if (pinyinCandidateStripExpanded == expanded) {
            return;
        }
        pinyinCandidateStripExpanded = expanded;
        renderBar();
    }

    /** Larger, easier-to-hit text than the compact English/status chips. */
    private TextView pinyinCandidateChip(String text, View.OnClickListener listener, int heightDp) {
        TextView candidate = chip(text, listener);
        candidate.setTextSize(heightDp < 48 ? 17 : 18);
        candidate.setMinWidth(dp(48));
        candidate.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(heightDp));
        params.setMargins(dp(2), 0, dp(2), 0);
        candidate.setLayoutParams(params);
        return candidate;
    }

    private void setInputMode(InputMode next) {
        hideKeyboardPicker();
        if (next == inputMode) {
            return;
        }
        settlePinyin(PinyinSession.SettleReason.MODE_CHANGED);
        inputMode = next;
        symbolsMode = false;
        symbolPage = SymbolPage.PRIMARY;
        shift = false;
        autoShift = false;
        capsLock = false;
        lastShiftTapMs = 0;
        clearAutoCorrection();
        if (inputMode.isChinese()) {
            pinyinEngine().prepare(inputMode);
            PinyinConverter composer = pinyinEngine().composerFor(inputMode);
            if (composer == null) {
                pinyinSession = null;
                setStatus("Loading Chinese…");
            } else if (pinyinSession == null) {
                pinyinSession = new PinyinSession(composer);
            } else {
                pinyinSession.setComposer(composer);
            }
        } else {
            pinyinSession = null;
            // Coming back from Chinese, where typing assistance was suppressed
            // outright, so the lexicon may never have been asked for.
            englishEngine().prepare();
            setStatus("Ready");
        }
        if (keyboardPanel != null) {
            populateKeyboardPanel(keyboardPanel);
        }
        if (!inputMode.isChinese()) {
            updateAutoCapitalization();
        }
        showIdleChips();
    }

    // ------------------------------------------------ the language key (0.8.8)

    /** The second keyboard: the Chinese layout the toggle goes to. */
    private InputMode secondaryKeyboard() {
        return InputMode.chineseFor(Prefs.chineseLayout(this));
    }

    /** A tap on the language key, by the behaviour chosen in Settings. */
    private void onLanguageKeyTap() {
        if (recording || processing) {
            return;
        }
        if (keyboardPickerOpen()) {
            hideKeyboardPicker();
            return;
        }
        InputMode next = LanguageKey.onTap(Prefs.languageKeyBehaviour(this), inputMode,
                secondaryKeyboard(), chineseAvailable());
        if (next == null) {
            showKeyboardPicker();
        } else {
            setInputMode(next);
        }
    }

    /**
     * She picked a keyboard by hand (the list or the More menu): a Chinese one
     * becomes the second keyboard, and the keyboard switches to it. English
     * leaves the second keyboard as it was.
     */
    private void chooseKeyboard(InputMode chosen) {
        if (recording || processing) {
            return;
        }
        if (chosen.isChinese()) {
            Prefs.setChineseLayout(this, chosen.prefsLayout());
            chineseLayoutSeen = Prefs.chineseLayout(this);
        }
        boolean already = chosen == inputMode;
        setInputMode(chosen);
        if (already && keyboardPanel != null && !composingPinyin()) {
            // Same keyboard, new second keyboard: the key's description changes.
            populateKeyboardPanel(keyboardPanel);
        }
    }

    /** The language key shows the keyboard on screen now: EN, 中 or 九. */
    private TextView languageKey() {
        TextView key = keyButton(inputMode.keyLabel(), 1.15f, v -> onLanguageKeyTap(), true);
        key.setContentDescription(LanguageKey.contentDescription(Prefs.languageKeyBehaviour(this),
                inputMode, secondaryKeyboard(), chineseAvailable()));
        key.setOnLongClickListener(v -> {
            haptic(v);
            showKeyboardPicker();
            return true;
        });
        return key;
    }

    private boolean keyboardPickerOpen() {
        return keyboardPicker != null && keyboardPicker.getVisibility() == View.VISIBLE;
    }

    private void hideKeyboardPicker() {
        if (keyboardPicker == null || keyboardPicker.getVisibility() != View.VISIBLE) {
            return;
        }
        keyboardPicker.setVisibility(View.GONE);
        keyboardPicker.removeAllViews();
        if (keyboardColumn != null) {
            keyboardColumn.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    private boolean configurationJustChanged() {
        return lastConfigurationChangeMs >= 0
                && android.os.SystemClock.uptimeMillis() - lastConfigurationChangeMs < 2_000;
    }

    /**
     * Puts the keyboard back on English when a new field started or the keyboard
     * was closed. Never mid-word: a composition stays on the keyboard it was typed on.
     */
    private void applyStartInEnglish() {
        if (!startInEnglish || rebuildingForConfiguration || recording || processing || keyboardPanel == null) {
            return;
        }
        startInEnglish = false;
        lastHideRequestMs = -1;
        if (inputMode == InputMode.ENGLISH || composingPinyin()) {
            return;
        }
        setInputMode(InputMode.ENGLISH);
    }

    /**
     * The list of keyboards, drawn inside the keyboard over its keys: English,
     * 中文 全键 and 中文 九宫格, the one on screen ticked and the second keyboard
     * marked. Picking one switches to it. A tap outside, Back, or the language
     * key closes it without typing anything; a composition stays as it is.
     */
    private void showKeyboardPicker() {
        if (keyboardPicker == null || recording || processing || !chineseAvailable()) {
            return;
        }
        hideVoiceStyleOverlay();
        keyboardPicker.removeAllViews();
        // The scrim: covers the bar and the keys so nothing under it is typed.
        keyboardPicker.setBackgroundColor(withAlpha(ui.scrim, 0x55));
        keyboardPicker.setClickable(true);
        keyboardPicker.setOnClickListener(v -> hideKeyboardPicker());
        keyboardPicker.setContentDescription("Close the keyboard list");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ui.surface);
        background.setCornerRadius(dp(14));
        background.setStroke(Math.max(1, dp(1)), ui.border);
        card.setBackground(background);
        card.setElevation(dp(6));

        TextView title = barText("Keyboards", 13, true, ui.subtle);
        title.setPadding(dp(10), dp(4), dp(10), dp(2));
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));

        LanguageKey.Behaviour behaviour = Prefs.languageKeyBehaviour(this);
        InputMode secondary = secondaryKeyboard();
        for (InputMode mode : LanguageKey.keyboards(chineseAvailable())) {
            card.addView(keyboardPickerRow(mode, mode == inputMode,
                    behaviour == LanguageKey.Behaviour.TOGGLE && mode == secondary));
        }

        TextView footer = barText(behaviour == LanguageKey.Behaviour.TOGGLE
                ? "Tap " + inputMode.keyLabel() + ": English ⇄ " + LanguageKey.shortName(secondary)
                : LanguageKey.describe(behaviour, secondary), 12, false, ui.muted);
        footer.setPadding(dp(10), dp(2), dp(10), dp(4));
        footer.setSingleLine(true);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        int rows = LanguageKey.keyboards(chineseAvailable()).size();
        int cardHeight = dp(26) + dp(44) * rows + dp(24) + dp(8);
        int surface = keyboardSurface != null && keyboardSurface.getLayoutParams() != null
                && keyboardSurface.getLayoutParams().height > 0
                ? keyboardSurface.getLayoutParams().height
                : dp(metrics.keyHeightDp * 4);
        int available = dp(BAR_HEIGHT_DP) + surface;
        // Just above the bottom row when it fits, so it rises from the key she held.
        int bottomMargin = available - keyHeightPx() - dp(4) >= cardHeight ? keyHeightPx() + dp(2) : dp(4);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.min(dp(280), Math.max(dp(200), getResources().getDisplayMetrics().widthPixels - dp(16))),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | (inputMode.isKeypad() ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        params.setMargins(dp(8), dp(4), dp(8), bottomMargin);
        keyboardPicker.addView(card, params);
        keyboardPicker.setVisibility(View.VISIBLE);
        // The scrim blocks touches; TalkBack must not reach the keys under it either.
        if (keyboardColumn != null) {
            keyboardColumn.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        View first = card.getChildCount() > 1 ? card.getChildAt(1) : card;
        first.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }

    private View keyboardPickerRow(InputMode mode, boolean current, boolean secondary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setClickable(true);
        GradientDrawable rowBackground = new GradientDrawable();
        rowBackground.setCornerRadius(dp(10));
        rowBackground.setColor(current ? ui.accentTint : android.graphics.Color.TRANSPARENT);
        row.setBackground(rowBackground);
        row.setContentDescription(LanguageKey.name(mode) + (current ? ", on screen now" : "")
                + (secondary ? ", the language key switches to it" : ""));
        row.setOnClickListener(v -> {
            haptic(v);
            hideKeyboardPicker();
            chooseKeyboard(mode);
        });

        ImageView tick = new ImageView(this);
        tick.setImageResource(R.drawable.ph_check);
        tick.setColorFilter(ui.accentInk);
        tick.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
        tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(tick, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView name = barText(LanguageKey.name(mode), 15, current, current ? ui.accentInk : ui.fg);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(10), 0, dp(6), 0);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (secondary) {
            TextView mark = barText("EN ⇄", 12, true, ui.subtle);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(mark);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        row.setLayoutParams(params);
        return row;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * The nine-key (九宫格) pad, 0.8.7, laid out as Sogou and Gboard do:
     *
     * <pre>
     *  ，   | 1 分词 | 2 ABC | 3 DEF  | ⌫
     *  。   | 4 GHI  | 5 JKL | 6 MNO  | 重输
     *  ？…  | 7 PQRS | 8 TUV | 9 WXYZ | ⏎
     *  符   | 123    | ☺     | space  | 中/英 | (⏎)
     * </pre>
     *
     * The left column lists punctuation, and while composing the spellings the
     * first digits could be (64: ni, mi, m, n, o); tapping one pins it. On a
     * large or wide screen the pad keeps a thumb-sized width, centred, rather
     * than stretching edge to edge (see {@link KeyboardGeometry#keypadWidthDp}).
     */
    private void addKeypadRows(LinearLayout panel) {
        String[][] groups = {
                {"分词", "1"}, {"ABC", "2"}, {"DEF", "3"},
                {"GHI", "4"}, {"JKL", "5"}, {"MNO", "6"},
                {"PQRS", "7"}, {"TUV", "8"}, {"WXYZ", "9"}
        };
        int rowHeight = keyHeightPx();
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.HORIZONTAL);

        // Left block: the side column and the 3x3 grid, over the bottom row.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout upper = new LinearLayout(this);
        upper.setOrientation(LinearLayout.HORIZONTAL);

        ScrollView side = new ScrollView(this);
        side.setVerticalScrollBarEnabled(false);
        side.setOverScrollMode(View.OVER_SCROLL_NEVER);
        keypadSideColumn = new LinearLayout(this);
        keypadSideColumn.setOrientation(LinearLayout.VERTICAL);
        side.addView(keypadSideColumn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        keypadSideScroller = side;
        keypadSideSignature = null;
        upper.addView(side, new LinearLayout.LayoutParams(0, rowHeight * 3, KEYPAD_SIDE_WEIGHT));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                String[] group = groups[row * 3 + column];
                final String digit = group[1];
                TextView key = "1".equals(digit)
                        ? keyButton(group[0], 1f, v -> typeKeypadSeparator())
                        : keyButton(group[0], 1f, v -> commitKey(digit));
                // keyButton sizes text by label length, which would render ABC at
                // 18sp next to PQRS at 11sp. The keypad wants one uniform size.
                key.setTextSize(15);
                key.setTypeface(Typeface.DEFAULT_BOLD);
                // The digit small above its letters, as on a phone keypad.
                android.text.SpannableString label = new android.text.SpannableString(digit + "\n" + group[0]);
                label.setSpan(new android.text.style.RelativeSizeSpan(0.72f), 0, digit.length(), 0);
                label.setSpan(new android.text.style.ForegroundColorSpan(ui.subtle), 0, digit.length(), 0);
                key.setSingleLine(false);
                key.setMaxLines(2);
                key.setLineSpacing(0f, 0.95f);
                key.setText(label);
                key.setContentDescription("1".equals(digit)
                        ? "Syllable break" : digit + " " + group[0]);
                line.addView(key);
            }
            grid.addView(line);
        }
        upper.addView(grid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f));
        left.addView(upper);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        TextView symbols = keyButton("符", KEYPAD_SIDE_WEIGHT, v -> toggleSymbolsMode(), true);
        symbols.setContentDescription("Symbols");
        bottom.addView(symbols);
        TextView numbers = keyButton("123", 0.9f, v -> showLayer(KeyboardLayerRules.Layer.NUMPAD), true);
        numbers.setContentDescription("Numbers");
        bottom.addView(numbers);
        TextView emojiKey = keyButton("", 0.8f, v -> showEmojiPicker(), true);
        emojiKey.setBackground(iconKeyBackground(colors.keyAlt, R.drawable.ph_smiley, colors.text));
        emojiKey.setContentDescription("Open emoji keyboard");
        bottom.addView(emojiKey);
        bottom.addView(spaceKey(1.6f));
        bottom.addView(languageKey());
        left.addView(bottom);
        pad.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
                KEYPAD_SIDE_WEIGHT + 3f));

        // Right column: delete, re-enter, and a two-row return.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView delete = deleteKey();
        right.addView(delete, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight));
        keypadReenterKey = keyButton("重输", 1f, v -> reenterPinyin(), true);
        keypadReenterKey.setContentDescription("Clear what you typed");
        right.addView(keypadReenterKey,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight));
        TextView enter = returnKey(1f);
        right.addView(enter, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight * 2));
        pad.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        int widthDp = KeyboardGeometry.keypadWidthDp(
                getResources().getConfiguration().screenWidthDp - 2 * metrics.outerInsetDp - 8, metrics.split);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                widthDp > 0 ? dp(widthDp) : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(pad, params);
        refreshKeypadSide();
    }

    /** Share of the pad's width the side column (and 符 under it) takes, against 1 per digit column. */
    private static final float KEYPAD_SIDE_WEIGHT = 0.85f;

    /** Punctuation in the side column, commonest first; 省略号 types as a pair. */
    private static final String[] KEYPAD_PUNCTUATION = {
            "，", "。", "？", "！", "…", "、", "：", "；", "～", "“", "”", "（", "）", "《", "》", "@"
    };

    /**
     * Fills the side column: the spellings to pin while composing, punctuation
     * otherwise. Also dims 重输 when there is nothing to clear. Cheap to call on
     * every keystroke; it only rebuilds when what it shows changed.
     */
    private void refreshKeypadSide() {
        if (keypadSideColumn == null || !inputMode.isKeypad() || symbolsMode) {
            return;
        }
        boolean composing = composingPinyin();
        List<PinyinKeypadLookup.SpellingOption> options = composing
                ? pinyinSession.spellingOptions()
                : java.util.Collections.<PinyinKeypadLookup.SpellingOption>emptyList();
        String signature = composing ? "s:" + options + "@" + pinyinSession.rawText() : "p";
        if (keypadReenterKey != null) {
            keypadReenterKey.setAlpha(composing ? 1f : 0.45f);
        }
        if (signature.equals(keypadSideSignature)) {
            return;
        }
        keypadSideSignature = signature;
        keypadSideColumn.removeAllViews();
        int itemHeight = Math.round(keyHeightPx() * 0.75f);
        if (composing && !options.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                final int index = i;
                TextView item = keypadSideItem(options.get(i).label, itemHeight, v -> pinKeypadSpelling(index));
                item.setContentDescription("Spell " + options.get(i).label);
                if (i == 0) {
                    item.setTextColor(colors.accent);
                }
                keypadSideColumn.addView(item);
            }
        } else {
            for (String mark : KEYPAD_PUNCTUATION) {
                String typed = "…".equals(mark) ? "……" : mark;
                keypadSideColumn.addView(keypadSideItem(mark, itemHeight, v -> commitKeypadPunctuation(typed)));
            }
        }
        if (keypadSideScroller != null) {
            keypadSideScroller.scrollTo(0, 0);
        }
    }

    /** A row of the side column: flat, on the utility key colour, outside keyButtons. */
    private TextView keypadSideItem(String label, int heightPx, View.OnClickListener listener) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(label.length() > 2 ? 15 : 17);
        item.setTypeface(Fonts.medium(this));
        item.setSingleLine(true);
        item.setGravity(Gravity.CENTER);
        item.setTextColor(colors.text);
        item.setBackground(keyVisualBackground(colors.keyAlt, false));
        item.setIncludeFontPadding(false);
        item.setClickable(true);
        item.setOnClickListener(v -> {
            if (recording || processing) {
                return;
            }
            haptic(v);
            listener.onClick(v);
        });
        item.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        return item;
    }

    /** Side-column punctuation: settles any reading first, as every separator does. */
    private void commitKeypadPunctuation(String mark) {
        clearBarMessage();
        if (recording || processing) {
            return;
        }
        if (mark.length() == 1 && "，。？！：；".contains(mark)) {
            commitSeparator(mark);
            return;
        }
        settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
        commitText(mark);
        showIdleChips();
    }

    /** Pins the spelling at {@code index} from the side column. */
    private void pinKeypadSpelling(int index) {
        if (pinyinSession == null || !composingPinyin()) {
            return;
        }
        if (pinyinSession.pinSpelling(index)) {
            refreshComposingText();
        }
    }

    /**
     * The 1 key (分词): a syllable break while composing; otherwise it types 1,
     * which is what its corner says.
     */
    private void typeKeypadSeparator() {
        clearBarMessage();
        if (recording || processing) {
            return;
        }
        if (composingPinyin()) {
            pinyinSession.appendSeparator();
            refreshComposingText();
            return;
        }
        commitText("1");
    }

    /** 重输: drops what is composing, leaving the field as it was. */
    private void reenterPinyin() {
        clearBarMessage();
        if (recording || processing || !composingPinyin()) {
            return;
        }
        settlePinyin(PinyinSession.SettleReason.CANCELLED);
        showIdleChips();
    }

    private void toggleSymbolsMode() {
        if (recording || processing || keyboardPanel == null) {
            return;
        }
        settlePinyin(PinyinSession.SettleReason.MODE_CHANGED);
        symbolsMode = !symbolsMode;
        symbolPage = SymbolPage.PRIMARY;
        shift = false;
        autoShift = false;
        lastShiftTapMs = 0;
        populateKeyboardPanel(keyboardPanel);
        if (!symbolsMode) {
            updateAutoCapitalization();
        }
        setStatus(symbolsMode ? "Symbols" : capsLock ? "Caps lock" : "Ready");
    }

    private void toggleMoreSymbolsMode() {
        if (recording || processing || keyboardPanel == null) {
            return;
        }
        haptic(keyboardPanel);
        symbolPage = symbolPage == SymbolPage.MORE ? SymbolPage.PRIMARY : SymbolPage.MORE;
        populateKeyboardPanel(keyboardPanel);
        setStatus(layerStatus(currentLayer()));
    }

    private void toggleVoiceCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (translationCapture || creationCapture || instructionCapture) {
                setStatus(translationCapture
                        ? "Tap the translate button to finish"
                        : creationCapture
                                ? "Tap the create button to finish"
                                : "Tap the instruction button to finish");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!recording && !shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in this field");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        // Resolve any pending reading before audio starts, so the buffer cannot
        // interleave with the transcript.
        settlePinyin(PinyinSession.SettleReason.RECORDING_STARTED);
        // 0.9.3: the style's own speech model, decided now, before cloud or offline routing.
        // A style switched on the recording panel keeps the model chosen here.
        SpeechModels.Resolved speech = SpeechModels.atRecordStart(this, choice.presetAtStart(defaultPreset()));
        String provider = speech.used.provider;
        boolean chinese = dictationLanguage().isChinese();

        // The bundled offline models are English-only. Left alone they would
        // transcribe Mandarin into confident nonsense rather than failing, which
        // is worse than refusing: the speaker gets no signal anything went wrong.
        if (chinese && isOfflineTranscriptionProvider(provider)) {
            setStatus("Chinese needs OpenAI or Deepgram");
            return;
        }
        if (chinese && !Prefs.supportsChineseDictation(provider)) {
            setStatus("Chinese not supported by " + Prefs.providerLabel(provider));
            return;
        }
        if (chinese && !hasNetworkConnectivity()) {
            // Do not silently drop to the offline English model.
            setStatus("Chinese dictation needs a connection");
            return;
        }

        recordingSpeech = speech;
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else if (!hasNetworkConnectivity()) {
            toggleOfflineFallbackRecording(provider);
        } else {
            toggleCloudRecording();
        }
    }

    /**
     * Language for the recording about to start. Snapshotted here rather than
     * read later, because the providers need it at request-build time and
     * Deepgram in particular can only be told one language.
     */
    private DictationLanguage dictationLanguage() {
        return inputMode.isChinese() ? DictationLanguage.CHINESE : DictationLanguage.AUTO;
    }

    private void toggleCreationCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (!creationCapture || translationCapture || instructionCapture) {
                setStatus("Finish or cancel the current recording first");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Text creation requires a connection");
            return;
        }
        String transformProvider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, transformProvider)) {
            setStatus("Add a " + Prefs.providerLabel(transformProvider) + " key for text creation");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        creationCapture = true;
        recordingSpeech = null;
        String provider = Prefs.transcriptionProvider(this);
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else {
            toggleCloudRecording();
        }
        if (!recording && !processing) {
            creationCapture = false;
            setCreateVisual(false, true);
            setMicVisual(false, true);
        }
    }

    private void toggleInstructionCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (!instructionCapture || translationCapture || creationCapture) {
                setStatus("Finish or cancel the current recording first");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Voice instructions require a connection");
            return;
        }
        String transformProvider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, transformProvider)) {
            setStatus("Add a " + Prefs.providerLabel(transformProvider) + " key for voice instructions");
            return;
        }
        String sourceText = currentEditableFieldText();
        if (sourceText.trim().isEmpty()) {
            setStatus("Add text to the field first");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        instructionCapture = true;
        instructionSourceText = sourceText;
        recordingSpeech = null;
        String provider = Prefs.transcriptionProvider(this);
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else {
            toggleCloudRecording();
        }
        if (!recording && !processing) {
            instructionCapture = false;
            instructionSourceText = "";
            setInstructionVisual(false, true);
            setMicVisual(false, true);
        }
    }

    private void toggleTranslationCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (!translationCapture) {
                setStatus("Finish or cancel the current recording first");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Translation requires a connection");
            return;
        }
        String transformProvider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, transformProvider)) {
            setStatus("Add a " + Prefs.providerLabel(transformProvider) + " key for translation");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        translationCapture = true;
        translationTargetLanguage = Prefs.translationTargetLanguage(this);
        recordingSpeech = null;
        String provider = Prefs.transcriptionProvider(this);
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else {
            toggleCloudRecording();
        }
        if (!recording && !processing) {
            translationCapture = false;
            translationTargetLanguage = "";
            setTranslationVisual(false, true);
            setInstructionVisual(false, true);
            setMicVisual(false, true);
        }
    }

    /** Proofreads the selection when present, otherwise the whole editable field. */
    private void correctSpellingOnly() {
        if (processing) {
            return;
        }
        if (recording) {
            setStatus("Finish or cancel the recording first");
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Spelling correction is disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Spelling correction requires a connection");
            return;
        }
        String provider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, provider)) {
            setStatus("Add a " + Prefs.providerLabel(provider) + " key for spelling correction");
            return;
        }
        CorrectionTarget target = currentCorrectionTarget();
        String sourceText = target == null ? "" : target.sourceText;
        if (sourceText.trim().isEmpty()) {
            setStatus("Add text to the field first");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }

        processing = true;
        setKeyboardLocked(true);
        hideChipStrip();
        updateRecordingControls();
        startStatusSpinner("Checking spelling");
        executor.execute(() -> {
            try {
                String result = TransformClient.correctSpellingOnly(this, sourceText);
                mainHandler.post(() -> {
                    // The app can still mutate its field while our keyboard is
                    // locked. Never overwrite newer text with a stale result.
                    CorrectionTarget current = currentCorrectionTarget();
                    if (!target.matches(current)) {
                        finishProcessingState("Text changed; spelling result not applied");
                    } else if (sourceText.equals(result)) {
                        finishProcessingState("No spelling changes");
                    } else if (replaceCorrectionTarget(target, result)) {
                        finishProcessingState(target.selectionOnly
                                ? "Selection spelling corrected"
                                : "Spelling corrected");
                    } else {
                        finishProcessingState("Could not replace this field");
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> finishProcessingState(concise(error)));
            }
        });
    }

    /**
     * Snapshots both the source and exact editor range. Editors such as rich
     * note fields do not always implement the contextual "Select all" action,
     * so correction replaces this range directly instead.
     */
    private CorrectionTarget currentCorrectionTarget() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return null;
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        CharSequence selected = connection.getSelectedText(0);

        if (selected != null && selected.length() > 0) {
            int[] range = selectedRange(extracted, selected.toString());
            return new CorrectionTarget(selected.toString(), true, range[0], range[1]);
        }
        if (extracted != null && extracted.text != null) {
            int relativeStart = Math.min(extracted.selectionStart, extracted.selectionEnd);
            int relativeEnd = Math.max(extracted.selectionStart, extracted.selectionEnd);
            if (relativeStart >= 0
                    && relativeEnd > relativeStart
                    && relativeEnd <= extracted.text.length()) {
                return new CorrectionTarget(
                        extracted.text.subSequence(relativeStart, relativeEnd).toString(),
                        true,
                        extracted.startOffset + relativeStart,
                        extracted.startOffset + relativeEnd
                );
            }
            int start = Math.max(0, extracted.startOffset);
            return new CorrectionTarget(
                    extracted.text.toString(),
                    false,
                    start,
                    start + extracted.text.length()
            );
        }

        CharSequence before = connection.getTextBeforeCursor(200000, 0);
        CharSequence after = connection.getTextAfterCursor(200000, 0);
        String fullText = String.valueOf(before == null ? "" : before)
                + String.valueOf(after == null ? "" : after);
        return new CorrectionTarget(fullText, false, 0, fullText.length());
    }

    /** Absolute selection range when ExtractedText agrees with getSelectedText. */
    private static int[] selectedRange(ExtractedText extracted, String selected) {
        if (extracted == null || extracted.text == null) {
            return new int[]{-1, -1};
        }
        int start = Math.min(extracted.selectionStart, extracted.selectionEnd);
        int end = Math.max(extracted.selectionStart, extracted.selectionEnd);
        if (start < 0 || end <= start || end > extracted.text.length()
                || !selected.contentEquals(extracted.text.subSequence(start, end))) {
            return new int[]{-1, -1};
        }
        return new int[]{extracted.startOffset + start, extracted.startOffset + end};
    }

    private boolean replaceCorrectionTarget(CorrectionTarget target, String replacement) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || replacement == null || target == null) {
            return false;
        }
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            boolean replaced;
            if (target.start >= 0 && target.end >= target.start) {
                replaced = replaceEditorRange(connection, target.start, target.end, replacement);
            } else if (target.selectionOnly) {
                // Some editors expose selected text but not selection offsets.
                // commitText still replaces the active selection.
                replaced = connection.commitText(replacement, 1);
            } else {
                return false;
            }
            if (replaced) {
                clearLastVoiceInsertion();
                clearAutoCorrection();
                updateAutoCapitalization();
            }
            return replaced;
        } finally {
            connection.endBatchEdit();
        }
    }

    private static boolean replaceEditorRange(
            InputConnection connection,
            int start,
            int end,
            String replacement
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && connection.replaceText(start, end, replacement, 1, null)) {
            return true;
        }
        return connection.setSelection(start, end) && connection.commitText(replacement, 1);
    }

    private static final class CorrectionTarget {
        final String sourceText;
        final boolean selectionOnly;
        final int start;
        final int end;

        CorrectionTarget(String sourceText, boolean selectionOnly, int start, int end) {
            this.sourceText = sourceText == null ? "" : sourceText;
            this.selectionOnly = selectionOnly;
            this.start = start;
            this.end = end;
        }

        boolean matches(CorrectionTarget other) {
            if (other == null
                    || selectionOnly != other.selectionOnly
                    || !sourceText.equals(other.sourceText)) {
                return false;
            }
            return start < 0 || other.start < 0 || (start == other.start && end == other.end);
        }
    }

    private String currentEditableFieldText() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted != null && extracted.text != null) {
            return extracted.text.toString();
        }
        CharSequence before = connection.getTextBeforeCursor(200000, 0);
        CharSequence selected = connection.getSelectedText(0);
        CharSequence after = connection.getTextAfterCursor(200000, 0);
        return String.valueOf(before == null ? "" : before)
                + String.valueOf(selected == null ? "" : selected)
                + String.valueOf(after == null ? "" : after);
    }

    private void cancelRecording() {
        if (!recording || processing) {
            return;
        }
        File audio = currentAudioFile;
        File pcm = currentPcmFile;
        if (offlineRecordingSession) {
            stopOfflineRecorderOnly();
        } else {
            stopCloudRecorderOnly();
        }
        deleteTempFile(audio);
        deleteTempFile(pcm);
        finishProcessingState("Recording canceled.");
    }

    private void toggleOfflineFallbackRecording(String selectedProvider) {
        if (recording) {
            stopOfflineRecordingAndTranscribe();
            return;
        }
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (!hasAudioPermission()) {
            setStatus("Open settings and grant microphone permission.");
            openSettings();
            return;
        }
        String fallbackProvider = installedOfflineFallbackProvider();
        if (fallbackProvider == null) {
            setStatus("No connection. Connect once to download offline model.");
            return;
        }
        startOfflineRecordingNow("Offline fallback from " + Prefs.providerLabel(selectedProvider), fallbackProvider);
    }

    private void toggleCloudRecording() {
        if (recording) {
            stopCloudRecordingAndTranscribe();
            return;
        }
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (!hasAudioPermission()) {
            setStatus("Open settings and grant microphone permission.");
            openSettings();
            return;
        }
        try {
            choice.startRecording(defaultPreset(), defaultExpression());
            currentAudioFile = File.createTempFile("voiceflow-keyboard-", ".m4a", getCacheDir());
            recorder = createRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(currentAudioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordingMayRemember = canRememberFromThisField();
            offlineRecordingSession = false;
            freezeRecordingTarget();
            setKeyboardLocked(true);
            showCaptureRecordingState();
        } catch (Exception e) {
            stopRecorderSilently();
            setStatus("Recording failed: " + concise(e));
        }
    }

    /** Fixes, at record start, where this dictation may go and which corrections it gets. */
    private void freezeRecordingTarget() {
        discardPendingRetry();
        recordingTarget = currentDeliveryTarget();
        // The word lists in force for this dictation: the sync server's lists merged with this
        // phone's own (or, before the first sync, the phone's own list as before),
        // with the words for the app it goes into.
        // Never reads or merges here (main thread): it takes the snapshot built ahead.
        recordingWords = WordLists.forRecordStart(this, recordingTarget.packageName);
        recordingCorrections = new ArrayList<>(recordingWords.corrections);
        recordingStartedAt = System.currentTimeMillis();
        panelKeptStyle = null;
        offlineLevel = 0f;
        if (waveform != null) {
            waveform.clear();
        }
        DictationActivity.report(true, System.currentTimeMillis());
    }

    private MediaRecorder createRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new MediaRecorder(this);
        }
        return new MediaRecorder();
    }

    private void stopCloudRecordingAndTranscribe() {
        File audio = currentAudioFile;
        stopCloudRecorderOnly();
        if (audio == null || !audio.exists() || audio.length() == 0) {
            finishProcessingState("No audio captured.");
            return;
        }
        processing = true;
        setKeyboardLocked(true);
        showCaptureProcessingState();
        startStatusSpinner(creationCapture
                ? "Transcribing creation request"
                : instructionCapture ? "Transcribing instruction" : translationCapture ? "Transcribing for translation" : "Transcribing");
        String presetForThisRecording = choice.preset;
        int expressionForThisRecording = choice.expression;
        boolean creationForThisRecording = creationCapture;
        boolean instructionForThisRecording = instructionCapture;
        String sourceForThisInstruction = instructionSourceText;
        boolean translationForThisRecording = translationCapture;
        String targetForThisTranslation = translationTargetLanguage;
        // An incognito or password field keeps no history: neither the field
        // the recording started in nor the one it stops in may forbid it.
        boolean rememberForThisRecording = recordingMayRemember && canRememberFromThisField();
        DictationLanguage languageForThisRecording = dictationLanguage();
        DeliveryGuard.Target targetForThisRecording = recordingTarget;
        List<PhraseReplacement> correctionsForThisRecording = recordingCorrections;
        WordLists.DictationWords wordsForThisRecording = recordingWords;
        SpeechModels.Resolved speechForThisRecording = recordingSpeech;
        recordingSpeech = null;
        runCloudCapture(new CloudCapture(
                audio,
                speechForThisRecording,
                presetForThisRecording,
                expressionForThisRecording,
                creationForThisRecording,
                instructionForThisRecording,
                sourceForThisInstruction,
                translationForThisRecording,
                targetForThisTranslation,
                rememberForThisRecording,
                languageForThisRecording,
                targetForThisRecording,
                correctionsForThisRecording,
                wordsForThisRecording
        ));
    }

    /** 0.9.3: everything one cloud recording needs to be transcribed, or tried again. */
    private static final class CloudCapture {
        final File audio;
        /** Null: the app's own speech model (text creation, instructions, translation). */
        final SpeechModels.Resolved speech;
        final String preset;
        final int expression;
        final boolean creation;
        final boolean instruction;
        final String instructionSource;
        final boolean translation;
        final String targetLanguage;
        final boolean remember;
        final DictationLanguage language;
        final DeliveryGuard.Target target;
        final List<PhraseReplacement> corrections;
        final WordLists.DictationWords words;

        CloudCapture(File audio, SpeechModels.Resolved speech, String preset, int expression, boolean creation,
                     boolean instruction, String instructionSource, boolean translation, String targetLanguage,
                     boolean remember, DictationLanguage language, DeliveryGuard.Target target,
                     List<PhraseReplacement> corrections, WordLists.DictationWords words) {
            this.audio = audio;
            this.speech = speech;
            this.preset = preset;
            this.expression = expression;
            this.creation = creation;
            this.instruction = instruction;
            this.instructionSource = instructionSource;
            this.translation = translation;
            this.targetLanguage = targetLanguage;
            this.remember = remember;
            this.language = language;
            this.target = target;
            this.corrections = corrections;
            this.words = words;
        }

        /**
         * Kept for a retry only from a field that may keep a history (never incognito or a
         * password field), and only for a dictation.
         */
        boolean mayKeep() {
            return remember && !creation && !instruction && !translation;
        }
    }

    /**
     * The style's speech model, then at most once the app default ({@link SpeechModels#run}).
     * A cancel ends both and nothing is inserted. The text goes on exactly once: only the
     * request that still owns {@link #speechCancel} may deliver it.
     */
    private void runCloudCapture(CloudCapture c) {
        SpeechCancel cancel = new SpeechCancel();
        synchronized (speechLock) {
            speechCancel = cancel;
        }
        mainHandler.post(this::renderBar);
        executor.execute(() -> {
            boolean keep = false;
            try {
                SpeechModels.Choice app = SpeechModels.appDefault(this);
                SpeechModels.Resolved speech = c.speech != null ? c.speech : new SpeechModels.Resolved(app, false, null);
                SpeechModels.Choice fallback = SpeechModels.fallback(speech, app,
                        Prefs.speechFallbackToAppDefault(this), SpeechModels.availability(this));
                SpeechModels.Outcome outcome = SpeechModels.run(speech, fallback,
                        (choice, appDefault) -> TranscriptionClient.transcribeWith(
                                this, choice, appDefault, c.audio, c.language, c.words, cancel),
                        cancel);
                if (!claimSpeech(cancel)) {
                    return; // canceled: the bar is already back, and nothing is inserted
                }
                String notice = outcome.notice();
                if (notice.isEmpty() && speech.unavailable != null) {
                    notice = Prefs.providerLabel(speech.unavailable.provider) + " unavailable, used "
                            + Prefs.providerLabel(outcome.used.provider);
                }
                if (!notice.isEmpty()) {
                    Prefs.addModelSwitchNotice(this, notice);
                }
                processTranscribedCapture(
                        outcome.text,
                        c.preset,
                        c.expression,
                        c.creation,
                        c.instruction,
                        c.instructionSource,
                        c.translation,
                        c.targetLanguage,
                        c.remember,
                        c.target,
                        c.corrections,
                        c.words.tidyWords
                );
            } catch (Exception e) {
                if (!claimSpeech(cancel)) {
                    return;
                }
                keep = c.mayKeep() && c.audio.exists();
                boolean kept = keep;
                String message = concise(e);
                mainHandler.post(() -> {
                    if (kept) {
                        discardPendingRetry();
                        pendingRetry = c;
                    }
                    finishProcessingState(kept ? message + " - recording kept, tap Retry" : message);
                });
            } finally {
                if (!keep && !c.audio.delete()) {
                    c.audio.deleteOnExit();
                }
            }
        });
    }

    /** True for the one request still allowed to deliver; it gives up the cancel. */
    private boolean claimSpeech(SpeechCancel cancel) {
        synchronized (speechLock) {
            if (speechCancel != cancel || cancel.isCancelled()) {
                return false;
            }
            speechCancel = null;
        }
        mainHandler.post(this::renderBar);
        return true;
    }

    /** 0.9.3: the working bar's cancel, while the speech request (or its fallback) runs. */
    private void cancelSpeechRequest() {
        SpeechCancel cancel;
        synchronized (speechLock) {
            cancel = speechCancel;
            speechCancel = null;
        }
        if (cancel == null) {
            return;
        }
        cancel.cancel();
        finishProcessingState("Canceled - nothing inserted");
    }

    private void retryPendingCapture() {
        CloudCapture c = pendingRetry;
        if (c == null || processing || recording) {
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Retry needs a connection");
            return;
        }
        pendingRetry = null;
        processing = true;
        setKeyboardLocked(true);
        showCaptureProcessingState();
        startStatusSpinner("Transcribing");
        runCloudCapture(c);
    }

    private void discardPendingRetry() {
        CloudCapture c = pendingRetry;
        pendingRetry = null;
        if (c != null && !c.audio.delete()) {
            c.audio.deleteOnExit();
        }
    }

    /** 0.9.3: Retry or Discard a dictation whose speech models all failed. */
    private boolean showRetryChip() {
        if (pendingRetry == null
                || chipStrip == null
                || recording
                || processing
                || historyMode
                || retoneMode
                || emojiPicker != null) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        TextView retry = chip("Retry dictation", v -> retryPendingCapture());
        retry.setContentDescription("Retry the dictation that failed");
        chipStrip.addView(retry);
        TextView discard = chip("Discard", v -> {
            discardPendingRetry();
            showIdleChips();
            setStatus("Recording discarded");
        });
        discard.setContentDescription("Discard the recording that failed");
        chipStrip.addView(discard);
        return true;
    }

    private boolean shouldTransform(String preset) {
        return Prefs.enableTransform(this)
                && !Prefs.PRESET_RAW.equals(preset)
                && hasNetworkConnectivity();
    }

    private void toggleOfflineRecording(String provider) {
        if (recording) {
            stopOfflineRecordingAndTranscribe();
            return;
        }
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (!hasAudioPermission()) {
            setStatus("Open settings and grant microphone permission.");
            openSettings();
            return;
        }
        if (!isOfflineModelReady(provider)) {
            prepareOfflineModel(provider);
            return;
        }
        startOfflineRecordingNow("Recording offline", provider);
    }

    private void prepareOfflineModel(String provider) {
        processing = true;
        setKeyboardLocked(true);
        micButton.setEnabled(false);
        setMicVisual(true, false);
        startStatusSpinner("Downloading " + Prefs.providerLabel(provider));
        executor.execute(() -> {
            try {
                ensureOfflineModel(provider);
                mainHandler.post(() -> finishProcessingState(Prefs.providerLabel(provider) + " ready. Tap mic to record."));
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState("Offline setup failed: " + concise(e)));
            }
        });
    }

    private void startOfflineRecordingNow(String statusPrefix, String provider) {
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Open settings and grant microphone permission.");
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                offlineSampleRate(provider),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            setStatus("Offline recorder unavailable.");
            return;
        }
        int bufferSize = Math.max(minBuffer, offlineSampleRate(provider) * 2);
        choice.startRecording(defaultPreset(), defaultExpression());
        try {
            currentPcmFile = File.createTempFile("voiceflow-keyboard-", ".pcm", getCacheDir());
            offlineRecorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    offlineSampleRate(provider),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
            if (offlineRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                stopOfflineRecorderOnly();
                setStatus("Offline recorder failed to initialize.");
                return;
            }
            offlineRecordLoop = true;
            offlineRecorder.startRecording();
            File target = currentPcmFile;
            AudioRecord activeRecorder = offlineRecorder;
            offlineRecordThread = new Thread(
                    () -> writeOfflinePcm(activeRecorder, target, bufferSize),
                    "VoiceFlowOfflineRecorder"
            );
            offlineRecordThread.start();
            recording = true;
            recordingMayRemember = canRememberFromThisField();
            offlineRecordingSession = true;
            offlineRecordingProvider = provider;
            freezeRecordingTarget();
            setKeyboardLocked(true);
            showCaptureRecordingState();
        } catch (Exception e) {
            stopOfflineRecorderOnly();
            setStatus("Offline recording failed: " + concise(e));
        }
    }

    private void writeOfflinePcm(AudioRecord activeRecorder, File target, int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        try (FileOutputStream out = new FileOutputStream(target)) {
            while (offlineRecordLoop) {
                int read = activeRecorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    out.write(buffer, 0, read);
                    offlineLevel = pcmLevel(buffer, read);
                }
            }
        } catch (Exception e) {
            if (offlineRecordLoop) {
                postStatus("Offline recording interrupted.");
            }
        }
    }

    private void stopOfflineRecordingAndTranscribe() {
        File pcm = currentPcmFile;
        stopOfflineRecorderOnly();
        if (pcm == null || !pcm.exists() || pcm.length() == 0) {
            finishProcessingState("No audio captured.");
            return;
        }
        processing = true;
        setKeyboardLocked(true);
        showCaptureProcessingState();
        startStatusSpinner(creationCapture
                ? "Transcribing creation request"
                : instructionCapture ? "Transcribing instruction" : translationCapture ? "Transcribing for translation" : "Transcribing");
        String presetForThisRecording = choice.preset;
        int expressionForThisRecording = choice.expression;
        String providerForThisRecording = offlineRecordingProvider;
        boolean creationForThisRecording = creationCapture;
        boolean instructionForThisRecording = instructionCapture;
        String sourceForThisInstruction = instructionSourceText;
        boolean translationForThisRecording = translationCapture;
        String targetForThisTranslation = translationTargetLanguage;
        // An incognito or password field keeps no history: neither the field
        // the recording started in nor the one it stops in may forbid it.
        boolean rememberForThisRecording = recordingMayRemember && canRememberFromThisField();
        DeliveryGuard.Target targetForThisRecording = recordingTarget;
        List<PhraseReplacement> correctionsForThisRecording = recordingCorrections;
        TidyWords.Block tidyWordsForThisRecording = recordingWords.tidyWords;
        executor.execute(() -> {
            try {
                String transcript = transcribeOfflinePcm(providerForThisRecording, pcm);
                processTranscribedCapture(
                        transcript,
                        presetForThisRecording,
                        expressionForThisRecording,
                        creationForThisRecording,
                        instructionForThisRecording,
                        sourceForThisInstruction,
                        translationForThisRecording,
                        targetForThisTranslation,
                        rememberForThisRecording,
                        targetForThisRecording,
                        correctionsForThisRecording,
                        tidyWordsForThisRecording
                );
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState(concise(e)));
            } finally {
                if (!pcm.delete()) {
                    pcm.deleteOnExit();
                }
            }
        });
    }

    private void processTranscribedCapture(
            String transcript,
            String preset,
            int expression,
            boolean creation,
            boolean instruction,
            String instructionSource,
            boolean translation,
            String targetLanguage,
            boolean rememberHistory,
            DeliveryGuard.Target target,
            List<PhraseReplacement> corrections,
            TidyWords.Block tidyWords
    ) throws Exception {
        // The transcript as captured. Dictation applies the frozen corrections once,
        // to the final text; translation, creation and voice edits get them once, on
        // their input, so a name is spelled right before it is translated.
        String spoken = transcript == null ? "" : transcript.trim();
        if (spoken.isEmpty()) {
            // 0.8.6, as desktop 0.9.11: nothing was heard, so nothing is sent on,
            // inserted or kept, and the bar says so instead of "Inserted".
            mainHandler.post(() -> finishProcessingState("Nothing heard - nothing inserted"));
            return;
        }
        String normalizedTranscript = PersonalVocabulary.applyReplacements(spoken, corrections);
        if (translation) {
            String statusLanguage = compactLanguageName(targetLanguage);
            postStatusSpinner("Translating to " + statusLanguage);
            String result = TransformClient.translate(
                    this,
                    normalizedTranscript,
                    targetLanguage,
                    preset,
                    expression
            );
            if (result == null || result.trim().isEmpty()) {
                throw new IllegalStateException("Translation returned no text.");
            }
            mainHandler.post(() -> {
                String historyId = !rememberHistory ? "" : Prefs.addTranscriptHistory(
                        this,
                        normalizedTranscript,
                        result,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_TRANSLATION,
                        targetLanguage
                );
                if (!deliverableTo(target)) {
                    finishProcessingState(copiedInstead(result, rememberHistory, historyId, true));
                    return;
                }
                String inserted = insertVoiceText(result);
                rememberLastVoiceInsertion(
                        normalizedTranscript,
                        inserted,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_TRANSLATION,
                        targetLanguage,
                        historyId
                );
                finishProcessingState(inserted.isEmpty()
                        ? copiedInstead(result, rememberHistory, historyId, false)
                        : "Translated - " + labelForPreset(preset));
            });
            return;
        }
        if (creation) {
            postStatusSpinner("Creating text");
            String result = TransformClient.createText(
                    this,
                    normalizedTranscript,
                    preset,
                    expression
            );
            if (result == null || result.trim().isEmpty()) {
                throw new IllegalStateException("Text creation returned no text.");
            }
            mainHandler.post(() -> {
                String historyId = !rememberHistory ? "" : Prefs.addTranscriptHistory(
                        this,
                        normalizedTranscript,
                        result,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_CREATION,
                        ""
                );
                if (!deliverableTo(target)) {
                    finishProcessingState(copiedInstead(result, rememberHistory, historyId, true));
                    return;
                }
                String inserted = appendCreatedText(result);
                if (inserted.isEmpty()) {
                    finishProcessingState(copiedInstead(result, rememberHistory, historyId, false));
                    return;
                }
                rememberLastVoiceInsertion(
                        normalizedTranscript,
                        inserted,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_CREATION,
                        "",
                        historyId
                );
                finishProcessingState("Created and appended");
            });
            return;
        }
        if (instruction) {
            postStatusSpinner("Applying instruction");
            String result = TransformClient.applyInstruction(
                    this,
                    instructionSource,
                    normalizedTranscript
            );
            mainHandler.post(() -> {
                // A voice edit rewrites the whole field, so it goes only into the
                // field it was started in, and only while that still holds the text
                // the edit was made from.
                if (DeliveryGuard.mayRewriteField(target, currentDeliveryTarget(), instructionSource, completeFieldText())
                        != DeliveryGuard.Verdict.OK) {
                    finishProcessingState("Field changed or unreadable - voice edit not applied");
                    return;
                }
                if (replaceWholeFieldText(result, instructionSource)) {
                    finishProcessingState("Text updated");
                } else {
                    finishProcessingState("Could not replace this field");
                }
            });
            return;
        }

        // Tidy-up, drift check and one strict retry; then the user's words or the
        // tidy-up; then the corrections, once; then mixed-script spacing.
        String chosen = spoken;
        String finalStatus = "Inserted";
        TransformClient.Outcome outcome = null;
        if (shouldTransform(preset)) {
            postStatusSpinner("Tidying up: " + labelForPreset(preset));
            try {
                outcome = TransformClient.transformChecked(this, spoken, preset, expression, tidyWords);
                chosen = outcome.text;
                if (outcome.fellBack()) {
                    finalStatus = "Kept your words - mixed languages, tidy-up held back";
                }
            } catch (Exception transformError) {
                finalStatus = "Inserted raw";
            }
        }
        String result = finishDictatedText(chosen, corrections);
        String heldBack = outcome != null && outcome.fellBack()
                ? finishDictatedText(outcome.heldBack, corrections)
                : "";
        if (outcome != null) {
            TransformClient.Outcome counted = outcome;
            recordEvidence(evidence -> evidence.recordCleanup(counted.mixed, counted.retried, counted.fallbackReason));
        }
        String status = finalStatus;
        mainHandler.post(() -> {
            String historyId = !rememberHistory ? "" : Prefs.addTranscriptHistory(
                    this,
                    spoken,
                    result,
                    preset,
                    expression,
                    VoiceHistoryItem.OPERATION_DICTATION,
                    ""
            );
            if (!historyId.isEmpty() && !heldBack.isEmpty()) {
                // History keeps Use cleaned after the bar is gone (desktop 0.9.13).
                Prefs.setHeldBackCleanup(this, historyId, heldBack, preset, expression);
            }
            if (!deliverableTo(target)) {
                finishProcessingState(copiedInstead(result, rememberHistory, historyId, true));
                return;
            }
            String inserted = insertVoiceText(result);
            rememberLastVoiceInsertion(
                    spoken,
                    inserted,
                    preset,
                    expression,
                    VoiceHistoryItem.OPERATION_DICTATION,
                    "",
                    historyId
            );
            if (!inserted.isEmpty()) {
                lastVoiceHeldBack = lastVoiceHistoryId.isEmpty() ? "" : heldBack;
                lastVoiceHeldBackReason = HELD_BACK_MIXED;
                recordEvidence(UpdateEvidence::recordDictation);
            }
            finishProcessingState(inserted.isEmpty()
                    ? copiedInstead(result, rememberHistory, historyId, false)
                    : status);
        });
    }

    /** Corrections once, then the mechanical mixed-script spacing. */
    private static String finishDictatedText(String text, List<PhraseReplacement> corrections) {
        String corrected = PersonalVocabulary.applyReplacements(text == null ? "" : text.trim(), corrections);
        return DictationText.normalizeMixedScript(corrected);
    }

    /** Whether a result may go into the field that is current now (the app it was started in). */
    private boolean deliverableTo(DeliveryGuard.Target target) {
        return DeliveryGuard.mayInsert(
                target,
                currentDeliveryTarget(),
                getCurrentInputConnection() != null
        ) == DeliveryGuard.Verdict.OK;
    }

    /** The editor that is current now, as {@link DeliveryGuard} compares editors. */
    private DeliveryGuard.Target currentDeliveryTarget() {
        EditorInfo info = getCurrentInputEditorInfo();
        return new DeliveryGuard.Target(info == null ? "" : info.packageName, fieldKey(info), editorSession);
    }

    /**
     * 0.8.4: the tidy-up's word list for a re-run (a retone or a History transform): the
     * words in force now, for the app the keyboard is in. On the main thread, like a
     * record start (never reads a file); never fails.
     */
    private TidyWords.Block currentTidyWords() {
        try {
            return WordLists.forRecordStart(this, currentDeliveryTarget().packageName).tidyWords;
        } catch (RuntimeException | LinkageError e) {
            return TidyWords.Block.EMPTY;
        }
    }

    /** The whole field, only when it can be shown to be complete; otherwise null. */
    private String completeFieldText() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return null;
        }
        int limit = 200000;
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        return DeliveryGuard.completeField(
                extracted == null ? null : extracted.text,
                extracted == null ? -1 : extracted.startOffset,
                connection.getTextBeforeCursor(limit, 0),
                connection.getSelectedText(0),
                connection.getTextAfterCursor(limit, 0),
                limit
        );
    }

    /**
     * Desktop's "Copied" fallback (0.9.11), on the phone in 0.8.6: a result that
     * could not go into the field it was started in goes to the clipboard, one
     * Paste away (the Paste chip offers it). Never from a field that keeps no
     * history (incognito or a password field): there nothing is copied and the
     * bar says only where it is not.
     *
     * @param movedAway true when the user moved to another field; false when the field
     *                  itself would not take the text
     * @return the bar's message
     */
    private String copiedInstead(String text, boolean mayCopy, String historyId, boolean movedAway) {
        boolean copied = false;
        if (mayCopy && text != null && !text.trim().isEmpty()) {
            try {
                ClipboardManager clipboard = clipboard();
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("VoiceFlow", text.trim()));
                    copied = true;
                }
            } catch (RuntimeException ignored) {
                // The clipboard refused (a work profile, a policy): say where it is instead.
            }
        }
        if (copied) {
            return movedAway
                    ? "Copied - you moved to another field. Tap Paste to put it here"
                    : "Copied - this field didn't take the text. Paste it where you want it";
        }
        return movedAway
                ? notDeliveredStatus(historyId)
                : historyId == null || historyId.isEmpty()
                        ? "Not inserted: this field didn't take the text"
                        : "Not inserted: this field didn't take the text - saved in History";
    }

    private static String notDeliveredStatus(String historyId) {
        return historyId == null || historyId.isEmpty()
                ? "Not inserted: you moved to another field"
                : "Not inserted: you moved to another field - saved in History";
    }

    /** Update and status counters live off the main thread; they never hold dictated text. */
    private void recordEvidence(SyncStore.EvidenceChange change) {
        android.content.Context app = getApplicationContext();
        maintenanceExecutor.execute(() -> {
            try {
                SyncStore.updateEvidence(app, SelfUpdater.installedVersionCode(app), change);
            } catch (RuntimeException ignored) {
                // Counters must never disturb dictation.
            }
        });
    }

    private void cyclePreset(int direction) {
        String[] presets = Prefs.selectablePresetValues(this);
        int index = Prefs.presetIndex(presets, choice.preset);
        int next = (index + direction + presets.length) % presets.length;
        choice.pick(presets[next], Prefs.expressionForPreset(this, presets[next]));
        refreshStyleChips();
        if (styleSheetOpen()) {
            showVoiceStyleOverlay();
        } else if (capturePanel && recording) {
            List<String> ids = panelStyleIds();
            panelKeptStyle = RecordingChips.nextKept(ids, panelPins(ids), choice.preset, panelKeptStyle);
            renderRecordingPanel();
        }
        announce("Style " + VoiceBar.styleLabel(labelForPreset(choice.preset), choice.expression) + " for this message");
    }

    private int presetIndex(String preset) {
        return Prefs.presetIndex(Prefs.selectablePresetValues(this), preset);
    }

    private String selectedPresetLabel() {
        return labelForPreset(choice.preset);
    }

    private String labelForPreset(String preset) {
        return Prefs.labelForPreset(this, preset);
    }

    private void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private void stopRecorderSilently() {
        stopCloudRecorderOnly();
        stopOfflineRecorderOnly();
        finishProcessingState("Ready");
    }

    private void stopCloudRecorderOnly() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
            recorder = null;
        }
        recording = false;
        offlineRecordingSession = false;
        offlineRecordingProvider = Prefs.PROVIDER_OFFLINE_VOSK;
        currentAudioFile = null;
    }

    private void stopOfflineRecorderOnly() {
        offlineRecordLoop = false;
        AudioRecord activeRecorder = offlineRecorder;
        offlineRecorder = null;
        if (activeRecorder != null) {
            try {
                activeRecorder.stop();
            } catch (Exception ignored) {
            }
            activeRecorder.release();
        }
        Thread thread = offlineRecordThread;
        offlineRecordThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        recording = false;
        offlineRecordingSession = false;
        offlineRecordingProvider = Prefs.PROVIDER_OFFLINE_VOSK;
        currentPcmFile = null;
    }

    private void finishProcessingState(String status) {
        DictationActivity.report(false, System.currentTimeMillis());
        stopStatusSpinner();
        recording = false;
        processing = false;
        // Inserted, cancelled or failed: the keys come back (renderBar hides the panel).
        capturePanel = false;
        panelKeptStyle = null;
        retoneMode = false;
        translationCapture = false;
        translationTargetLanguage = "";
        creationCapture = false;
        instructionCapture = false;
        instructionSourceText = "";
        stopDeleteHold();
        mainHandler.removeCallbacks(meterRunnable);
        hideVoiceStyleOverlay();
        setKeyboardLocked(false);
        // A style picked for one message ends with that message.
        resetChoiceToDefault();
        if (micButton != null) {
            setMicVisual(false, true);
        }
        // A new field or a closed keyboard during the recording asked for English;
        // settle it now, before the keys come back, not on some later restart.
        applyStartInEnglish();
        if (chipStrip != null) {
            showIdleChips();
        }
        setStatus(status);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOfflineTranscriptionProvider(String provider) {
        return Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)
                || Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider);
    }

    private String installedOfflineFallbackProvider() {
        if (OfflineParakeetClient.isModelReady(this)) {
            return Prefs.PROVIDER_OFFLINE_PARAKEET;
        }
        if (OfflineVoskClient.isModelReady(this)) {
            return Prefs.PROVIDER_OFFLINE_VOSK;
        }
        return null;
    }

    private boolean isOfflineModelReady(String provider) {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.isModelReady(this);
        }
        return OfflineVoskClient.isModelReady(this);
    }

    private void ensureOfflineModel(String provider) throws Exception {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            OfflineParakeetClient.ensureModel(this);
        } else {
            OfflineVoskClient.ensureModel(this);
        }
    }

    private String transcribeOfflinePcm(String provider, File pcm) throws Exception {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.transcribePcm(this, pcm);
        }
        return OfflineVoskClient.transcribePcm(this, pcm);
    }

    private int offlineSampleRate(String provider) {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.SAMPLE_RATE;
        }
        return OfflineVoskClient.SAMPLE_RATE;
    }

    private boolean hasNetworkConnectivity() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void postStatus(String status) {
        mainHandler.post(() -> setStatus(status));
    }

    private void startStatusSpinner(String base) {
        hideChipStrip();
        startMicLoading();
        renderBar();
        statusSpinnerBase = base;
        statusSpinnerStep = 0;
        if (statusSpinnerRunnable == null) {
            statusSpinnerRunnable = () -> {
                if (!processing) {
                    return;
                }
                String dots = statusSpinnerStep == 0 ? "." : statusSpinnerStep == 1 ? ".." : "...";
                setStatus(statusSpinnerBase + dots);
                statusSpinnerStep = (statusSpinnerStep + 1) % 3;
                mainHandler.postDelayed(statusSpinnerRunnable, 450);
            };
        }
        mainHandler.removeCallbacks(statusSpinnerRunnable);
        mainHandler.post(statusSpinnerRunnable);
    }

    private void postStatusSpinner(String base) {
        mainHandler.post(() -> startStatusSpinner(base));
    }

    private void stopStatusSpinner() {
        if (statusSpinnerRunnable != null) {
            mainHandler.removeCallbacks(statusSpinnerRunnable);
        }
        stopMicLoading();
        stopCreateLoading();
        stopInstructionLoading();
        stopTranslationLoading();
    }

    private void startMicLoading() {
        if (workingIcon == null) {
            return;
        }
        if (micLoadingAnimator == null) {
            micLoadingAnimator = ObjectAnimator.ofFloat(workingIcon, View.ROTATION, 0f, 360f);
            micLoadingAnimator.setDuration(850);
            micLoadingAnimator.setInterpolator(new LinearInterpolator());
            micLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!micLoadingAnimator.isStarted()) {
            micLoadingAnimator.start();
        }
    }

    private void stopMicLoading() {
        if (micLoadingAnimator != null) {
            micLoadingAnimator.cancel();
        }
        if (workingIcon != null) {
            workingIcon.setRotation(0f);
        }
    }

    private void startInstructionLoading() {
        if (instructionButton == null) {
            return;
        }
        instructionButton.setVisibility(View.VISIBLE);
        instructionButton.setEnabled(false);
        instructionButton.setImageResource(R.drawable.ic_loading_24);
        instructionButton.setColorFilter(colors.text);
        instructionButton.setBackground(ovalBackground(colors.key, false));
        instructionButton.setAlpha(1f);
        if (instructionLoadingAnimator == null) {
            instructionLoadingAnimator = ObjectAnimator.ofFloat(instructionButton, View.ROTATION, 0f, 360f);
            instructionLoadingAnimator.setDuration(850);
            instructionLoadingAnimator.setInterpolator(new LinearInterpolator());
            instructionLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!instructionLoadingAnimator.isStarted()) {
            instructionLoadingAnimator.start();
        }
    }

    private void stopInstructionLoading() {
        if (instructionLoadingAnimator != null) {
            instructionLoadingAnimator.cancel();
        }
        if (instructionButton != null) {
            instructionButton.setRotation(0f);
            instructionButton.setImageResource(R.drawable.ic_wand_24);
        }
    }

    private void startCreateLoading() {
        if (createButton == null) {
            return;
        }
        createButton.setVisibility(View.VISIBLE);
        createButton.setEnabled(false);
        createButton.setImageResource(R.drawable.ic_loading_24);
        createButton.setColorFilter(colors.text);
        createButton.setBackground(ovalBackground(colors.key, false));
        createButton.setAlpha(1f);
        if (createLoadingAnimator == null) {
            createLoadingAnimator = ObjectAnimator.ofFloat(createButton, View.ROTATION, 0f, 360f);
            createLoadingAnimator.setDuration(850);
            createLoadingAnimator.setInterpolator(new LinearInterpolator());
            createLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!createLoadingAnimator.isStarted()) {
            createLoadingAnimator.start();
        }
    }

    private void stopCreateLoading() {
        if (createLoadingAnimator != null) {
            createLoadingAnimator.cancel();
        }
        if (createButton != null) {
            createButton.setRotation(0f);
            createButton.setImageResource(R.drawable.ic_create_text_24);
        }
    }

    private void startTranslationLoading() {
        if (translationButton == null) {
            return;
        }
        translationButton.setVisibility(View.VISIBLE);
        translationButton.setEnabled(false);
        translationButton.setImageResource(R.drawable.ic_loading_24);
        translationButton.setColorFilter(colors.text);
        translationButton.setBackground(ovalBackground(colors.key, false));
        translationButton.setAlpha(1f);
        if (translationLoadingAnimator == null) {
            translationLoadingAnimator = ObjectAnimator.ofFloat(translationButton, View.ROTATION, 0f, 360f);
            translationLoadingAnimator.setDuration(850);
            translationLoadingAnimator.setInterpolator(new LinearInterpolator());
            translationLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!translationLoadingAnimator.isStarted()) {
            translationLoadingAnimator.start();
        }
    }

    private void stopTranslationLoading() {
        if (translationLoadingAnimator != null) {
            translationLoadingAnimator.cancel();
        }
        if (translationButton != null) {
            translationButton.setRotation(0f);
            translationButton.setImageResource(R.drawable.ic_translate_24);
        }
    }

    private void setStatus(String status) {
        if (statusText != null) {
            statusText.setText(status);
            statusText.setContentDescription(status);
        }
        if (processing || idleBar == null || VoiceBar.routineStatus(status)) {
            return;
        }
        if (recording) {
            if (recordingHintText != null) {
                recordingHintText.setText(status);
            }
            return;
        }
        showBarMessage(status);
    }

    private boolean voiceCallOwnsMicrophone() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }
        int mode = audioManager.getMode();
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private String concise(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable keyBackground(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        if (!selected) {
            drawable.setStroke(Math.max(1, dp(1)), ui.border);
        }
        return drawable;
    }

    /** A key: radius 7, a 1 dp shadow under it (B3.1), inset by the key gap. */
    private android.graphics.drawable.LayerDrawable keyVisualBackground(int color, boolean selected) {
        int gap = dp(KEY_VISUAL_GAP_DP);
        int lift = Math.max(1, dp(1));
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(ui.keyShadow);
        shadow.setCornerRadius(dp(7));
        GradientDrawable face = new GradientDrawable();
        face.setColor(color);
        face.setCornerRadius(dp(7));
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{shadow, face});
        layers.setLayerInset(0, gap, gap + lift, gap, gap - lift);
        layers.setLayerInset(1, gap, gap, gap, gap);
        return layers;
    }

    /** A key face with a centred icon (shift, delete, emoji, return). */
    private Drawable iconKeyBackground(int color, int iconRes, int iconColor) {
        android.graphics.drawable.LayerDrawable face = keyVisualBackground(color, false);
        return withKeyIcon(face, iconRes, iconColor);
    }

    /** The tonal key: accent tint and its edge, no shadow (return, shift on). */
    private Drawable tonalKeyBackground(int iconRes) {
        int gap = dp(KEY_VISUAL_GAP_DP);
        GradientDrawable face = new GradientDrawable();
        face.setColor(ui.accentTint);
        face.setCornerRadius(dp(7));
        face.setStroke(Math.max(1, dp(1)), ui.accentTintBorder);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{face});
        layers.setLayerInset(0, gap, gap, gap, gap);
        return withKeyIcon(layers, iconRes, ui.accentInk);
    }

    private Drawable withKeyIcon(android.graphics.drawable.LayerDrawable face, int iconRes, int iconColor) {
        Drawable icon = getDrawable(iconRes);
        if (icon == null) {
            return face;
        }
        icon = icon.mutate();
        icon.setTint(iconColor);
        int count = face.getNumberOfLayers();
        Drawable[] layers = new Drawable[count + 1];
        for (int i = 0; i < count; i++) {
            layers[i] = face.getDrawable(i);
        }
        layers[count] = icon;
        android.graphics.drawable.LayerDrawable out = new android.graphics.drawable.LayerDrawable(layers);
        for (int i = 0; i < count; i++) {
            out.setLayerInsetLeft(i, face.getLayerInsetLeft(i));
            out.setLayerInsetTop(i, face.getLayerInsetTop(i));
            out.setLayerInsetRight(i, face.getLayerInsetRight(i));
            out.setLayerInsetBottom(i, face.getLayerInsetBottom(i));
        }
        out.setLayerGravity(count, Gravity.CENTER);
        out.setLayerSize(count, dp(22), dp(22));
        return out;
    }

    /** Return, drawn for the field's action: send, search, go, next, done, or a new line. */
    private TextView returnKey(float weight) {
        TextView key = keyButton("", weight, v -> sendEnter(), true);
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        int icon = R.drawable.ph_arrow_elbow_down_left;
        String description = "Return";
        if (!noEnterAction) {
            switch (action) {
                case EditorInfo.IME_ACTION_SEND:
                    icon = R.drawable.ph_paper_plane_right;
                    description = "Send";
                    break;
                case EditorInfo.IME_ACTION_SEARCH:
                    icon = R.drawable.ph_magnifying_glass;
                    description = "Search";
                    break;
                case EditorInfo.IME_ACTION_GO:
                    icon = R.drawable.ph_arrow_right;
                    description = "Go";
                    break;
                case EditorInfo.IME_ACTION_NEXT:
                    icon = R.drawable.ph_arrow_right;
                    description = "Next";
                    break;
                case EditorInfo.IME_ACTION_DONE:
                    icon = R.drawable.ph_check;
                    description = "Done";
                    break;
                default:
                    break;
            }
        }
        key.setBackground(tonalKeyBackground(icon));
        key.setContentDescription(description);
        return key;
    }

    private GradientDrawable ovalBackground(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (!selected) {
            drawable.setStroke(dp(1), colors.stroke);
        }
        return drawable;
    }

    private void setMicVisual(boolean active, boolean enabled) {
        if (micButton == null) {
            return;
        }
        boolean usable = enabled && !active;
        micButton.setEnabled(usable || active);
        if (dictateButton != null) {
            dictateButton.setEnabled(usable || active);
        }
        updateRecordingControls();
    }

    private void setInstructionVisual(boolean active, boolean enabled) {
        if (instructionButton == null) {
            return;
        }
        if (!processing) {
            instructionButton.setImageResource(R.drawable.ic_wand_24);
        }
        instructionButton.setEnabled(enabled);
        instructionButton.setColorFilter(active ? colors.onDanger : colors.text);
        instructionButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        instructionButton.setAlpha(enabled ? 1f : 0.55f);
        updateRecordingControls();
    }

    private void setCreateVisual(boolean active, boolean enabled) {
        if (createButton == null) {
            return;
        }
        if (!processing) {
            createButton.setImageResource(R.drawable.ic_create_text_24);
        }
        createButton.setEnabled(enabled);
        createButton.setColorFilter(active ? colors.onDanger : colors.text);
        createButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        createButton.setAlpha(enabled ? 1f : 0.55f);
        updateRecordingControls();
    }

    private void setTranslationVisual(boolean active, boolean enabled) {
        if (translationButton == null) {
            return;
        }
        if (!processing) {
            translationButton.setImageResource(R.drawable.ic_translate_24);
        }
        translationButton.setEnabled(enabled);
        translationButton.setColorFilter(active ? colors.onDanger : colors.text);
        translationButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        translationButton.setAlpha(enabled ? 1f : 0.55f);
        updateRecordingControls();
    }

    private void showCaptureRecordingState() {
        // The style sheet never opens by itself: the panel's chips and slider are the
        // quick way, More opens the sheet on purpose.
        hideChipStrip();
        if (instructionCapture) {
            hideVoiceStyleOverlay();
        }
        capturePanel = true;
        setMicVisual(true, true);
        renderRecordingPanel();
        renderBar();
        mainHandler.removeCallbacks(meterRunnable);
        mainHandler.post(meterRunnable);
        announce(VoiceBar.listeningLabel(translationCapture, creationCapture, instructionCapture));
    }

    // ------------------------------------------------------------ recording panel

    /** The panel's frame, at the keys' place; its content is drawn by {@link #renderRecordingPanel()}. */
    private View buildRecordingPanel() {
        recordingPanel = new FrameLayout(this);
        recordingPanel.setBackgroundColor(ui.ime);
        recordingPanel.setVisibility(View.GONE);
        // Swallows touches, so nothing reaches the keys under it.
        recordingPanel.setClickable(true);
        recordingHintText = null;
        panelSlider = null;
        return recordingPanel;
    }

    /** Whether the panel stands in for the keys now: a dictation recording or being processed. */
    private boolean recordingPanelWanted(VoiceBar.Mode mode) {
        return capturePanel && (mode == VoiceBar.Mode.LISTENING || mode == VoiceBar.Mode.WORKING);
    }

    /** Shows the panel or the keys, as the bar's mode calls for; never both. */
    private void syncRecordingPanel(VoiceBar.Mode mode) {
        if (recordingPanel == null || keyboardPanel == null) {
            return;
        }
        boolean show = recordingPanelWanted(mode);
        if (show) {
            if (recordingPanel.getChildCount() == 0) {
                renderRecordingPanel();
            }
            recordingPanel.setVisibility(View.VISIBLE);
            // Invisible, not gone: the keys keep the surface's height, so nothing jumps.
            keyboardPanel.setVisibility(View.INVISIBLE);
        } else {
            if (recordingPanel.getVisibility() != View.GONE || recordingPanel.getChildCount() > 0) {
                recordingPanel.setVisibility(View.GONE);
                recordingPanel.removeAllViews();
                recordingHintText = null;
                panelSlider = null;
            }
            keyboardPanel.setVisibility(View.VISIBLE);
        }
    }

    /** The styles in their own order, and the user's pins (the sheet's "On the bar"), for the chips. */
    private List<String> panelStyleIds() {
        List<String> ids = new ArrayList<>();
        for (PromptProfile style : Prefs.promptProfiles(this)) {
            ids.add(style.id);
        }
        return ids;
    }

    private List<String> panelPins(List<String> ids) {
        List<String> funIds = new ArrayList<>();
        for (String id : ids) {
            if (Prefs.isFunVoiceStyle(id)) {
                funIds.add(id);
            }
        }
        return StylePins.onBar(ids, funIds, Prefs.pinnedStyles(this), null);
    }

    /**
     * Draws the recording panel: what to do now, the style chips (the user's pins, at most
     * five, the one in use always among them, More for the rest) and the expression
     * slider. A choice here is for this message only. After Stop the choice is fixed,
     * so the panel stays, dimmed, until the text is inserted.
     */
    private void renderRecordingPanel() {
        if (recordingPanel == null || !capturePanel) {
            return;
        }
        recordingPanel.removeAllViews();
        panelSlider = null;
        boolean frozen = processing || !recording;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // At most PANEL_MAX_WIDTH_DP wide, centred, so a Fold's inner screen keeps
        // the chips and the slider together.
        int available = Math.max(0, getResources().getConfiguration().screenWidthDp - 8);
        int contentWidthDp = Math.max(0, Math.min(available - 24, PANEL_MAX_WIDTH_DP));
        int sideDp = Math.max(12, (available - contentWidthDp) / 2);
        column.setPadding(dp(sideDp), dp(2), dp(sideDp), dp(2));
        scroll.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        recordingPanel.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        if (instructionCapture) {
            // A voice edit has no style: only what to do.
            column.setGravity(Gravity.CENTER);
            recordingHintText = barText(frozen ? "Applying your instruction to this text."
                    : "Say how to change the text, then tap Stop.", 14, false, ui.muted);
            recordingHintText.setGravity(Gravity.CENTER);
            recordingHintText.setMaxLines(3);
            column.addView(recordingHintText, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            return;
        }

        String title = translationCapture ? "Style for this translation"
                : creationCapture ? "Style for this text" : "Style for this message";
        recordingHintText = sheetLabel(frozen ? title + " · set" : title);
        recordingHintText.setSingleLine(true);
        recordingHintText.setEllipsize(TextUtils.TruncateAt.END);
        recordingHintText.setMinHeight(dp(20));
        column.addView(recordingHintText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        List<String> ids = panelStyleIds();
        RecordingChips.Row row = RecordingChips.row(ids, panelPins(ids), choice.preset, panelKeptStyle);
        FlowRow chips = new FlowRow(this, dp(6), 0);
        chips.setContentDescription("Styles");
        for (String id : row.chips) {
            TextView chip = sheetStyleChip(Prefs.promptProfile(this, id));
            chip.setTextSize(13f);
            chip.setPadding(dp(id.equals(choice.preset) ? 10 : 12), 0, dp(12), 0);
            chip.setEnabled(!frozen);
            chips.addView(chip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        if (!row.more.isEmpty()) {
            TextView more = outlinePill("More", 0);
            more.setTextSize(13f);
            Drawable caret = getDrawable(R.drawable.ph_caret_down);
            if (caret != null) {
                caret = caret.mutate();
                caret.setBounds(0, 0, dp(14), dp(14));
                caret.setTint(ui.muted);
                more.setCompoundDrawables(null, null, caret, null);
                more.setCompoundDrawablePadding(dp(4));
            }
            more.setPadding(dp(12), 0, dp(10), 0);
            more.setContentDescription("More styles: " + row.more.size());
            more.setEnabled(!frozen);
            more.setOnClickListener(v -> {
                if (!recording || processing) {
                    return;
                }
                haptic(v);
                openAllStylesNext = true;
                showVoiceStyleOverlay();
            });
            chips.addView(more, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        if (frozen) {
            chips.setAlpha(0.5f);
        }
        column.addView(chips, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        View expression = expressionControl(true, contentWidthDp);
        LinearLayout.LayoutParams expressionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        expressionParams.topMargin = dp(2);
        column.addView(expression, expressionParams);
    }

    /** The waveform and timer, about twelve times a second while recording. */
    private void updateMeter() {
        if (!recording || waveform == null) {
            return;
        }
        float level = 0f;
        if (offlineRecordingSession) {
            level = offlineLevel;
        } else if (recorder != null) {
            try {
                level = recorder.getMaxAmplitude() / 32767f;
            } catch (RuntimeException ignored) {
                // Not started yet or already stopped.
            }
        }
        // Speech sits low on a linear scale; a square root makes it visible.
        waveform.push((float) Math.sqrt(Math.max(0f, level)));
        timerText.setText(VoiceBar.elapsed(System.currentTimeMillis() - recordingStartedAt));
        mainHandler.postDelayed(meterRunnable, 80);
    }

    /** The peak of a 16-bit PCM buffer, 0 to 1. */
    private static float pcmLevel(byte[] buffer, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        return Math.min(1f, peak / 32767f);
    }

    private void showCaptureProcessingState() {
        mainHandler.removeCallbacks(meterRunnable);
        hideVoiceStyleOverlay();
        setMicVisual(false, false);
        // The panel stays until the text is inserted, its choice now fixed (dimmed).
        renderRecordingPanel();
        renderBar();
    }

    private void updateRecordingControls() {
        renderBar();
    }

    private void haptic(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private String captureStyleStatus(String prefix) {
        return prefix + ": " + selectedPresetLabel();
    }

    private String compactLanguageName(String language) {
        int qualifierStart = language.indexOf(" (");
        return qualifierStart > 0 ? language.substring(0, qualifierStart) : language;
    }

    /** The keys' colours, drawn from the shared {@link Palette}. */
    private static final class KeyColors {
        final int background;
        final int status;
        final int key;
        final int keyAlt;
        final int text;
        final int stroke;
        final int accent;
        final int onAccent;
        final int danger;
        final int onDanger;

        private KeyColors(
                int background,
                int status,
                int key,
                int keyAlt,
                int text,
                int stroke,
                int accent,
                int onAccent,
                int danger,
                int onDanger
        ) {
            this.background = background;
            this.status = status;
            this.key = key;
            this.keyAlt = keyAlt;
            this.text = text;
            this.stroke = stroke;
            this.accent = accent;
            this.onAccent = onAccent;
            this.danger = danger;
            this.onDanger = onDanger;
        }

        static KeyColors from(Palette ui) {
            return new KeyColors(
                    ui.ime,
                    ui.subtle,
                    ui.key,
                    ui.keyFn,
                    ui.keyFg,
                    ui.borderStrong,
                    ui.accent,
                    ui.onAccent,
                    ui.record,
                    Color.WHITE
            );
        }
    }

    private int resolveThemeColor(int attr, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return fallback;
    }

    private static Map<String, String> commonTypos() {
        Map<String, String> typos = new HashMap<>();
        typos.put("teh", "the");
        typos.put("hte", "the");
        typos.put("liek", "like");
        typos.put("becuase", "because");
        typos.put("becasue", "because");
        typos.put("definately", "definitely");
        typos.put("seperate", "separate");
        typos.put("recieve", "receive");
        typos.put("adress", "address");
        typos.put("wierd", "weird");
        typos.put("thier", "their");
        typos.put("freind", "friend");
        typos.put("dont", "don't");
        typos.put("cant", "can't");
        typos.put("wont", "won't");
        typos.put("im", "I'm");
        typos.put("ive", "I've");
        typos.put("ill", "I'll");
        return typos;
    }

    private final class SwipeRootLayout extends LinearLayout {
        SwipeRootLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean wasTracking = rootSwipeTracking;
            if (handleRootSwipe(event)) {
                if (!wasTracking && rootSwipeTracking) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
    }
}
