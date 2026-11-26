package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The VoiceFlow app: a calm home (status, then styles, words and history, then
 * keys, keyboard and appearance) and three pages behind it. Every setting from
 * earlier versions is on one of them.
 *
 * <p>Opening the app checks for an update and installs a verified one straight
 * away, once per opening (tapping "update ready" counts as opening). Google
 * Play Protect then shows its own dialog in front; this screen only says
 * "Updating VoiceFlow…" underneath it and never starts anything over it.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_JUST_UPDATED = "justUpdated";
    /** From the "update ready" notification: install now. */
    static final String EXTRA_INSTALL_UPDATE = "installUpdate";
    static final String PAGE_HOME = "home";
    static final String PAGE_STYLES = "styles";
    static final String PAGE_MODELS = "models";
    static final String PAGE_KEYBOARD = "keyboard";

    /** Whether a VoiceFlow settings screen is in front, for showing an update's confirm screen. */
    static volatile boolean visible;
    private static final AtomicBoolean INSTALL_CHECK_RUNNING = new AtomicBoolean();

    private TextView offlineFallbackValue;
    private TextView translationLanguageValue;
    private TextView chineseLayoutValue;
    private String page = PAGE_HOME;
    private boolean created;
    private boolean downloadingOfflineModel;
    /**
     * Openings of the app. An opening starts when the first VoiceFlow screen becomes
     * visible with none visible before; moving between its pages, or coming back
     * from Play Protect's dialog, is the same opening. Main thread only.
     */
    private static int startedScreens;
    private static long opening;
    /** The opening that has already tried the install. */
    private static long installTriedOpening = -1;
    private boolean installRequested;
    private final Runnable bannerRefresh = this::refreshForUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        String requested = getIntent().getStringExtra(EXTRA_PAGE);
        page = requested == null ? PAGE_HOME : requested;
        if (PAGE_HOME.equals(page)) {
            requestAudioPermission();
        }
        setTitle("VoiceFlow");
        installRequested = getIntent().getBooleanExtra(EXTRA_INSTALL_UPDATE, false);
        setContentView(buildContent());
        created = true;
        if (getIntent().getBooleanExtra(EXTRA_JUST_UPDATED, false)) {
            Toast.makeText(this, "Updated to VoiceFlow " + SelfUpdater.installedVersionName(this), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (startedScreens == 0) {
            opening++;
        }
        startedScreens++;
    }

    @Override
    protected void onStop() {
        startedScreens = Math.max(0, startedScreens - 1);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_INSTALL_UPDATE, false)) {
            installRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (created) {
            setContentView(buildContent());
        }
        // Opening the app is a sync trigger (at most every 15 minutes, a no-op until enrolled),
        // and the moment to install an update that is already downloaded and verified.
        android.content.Context app = getApplicationContext();
        new Thread(() -> SyncScheduler.onAppOpened(app), "VoiceFlowAppOpened").start();
        // The "update ready" notice installs from whichever page is on top.
        if (installRequested) {
            installRequested = false;
            installTriedOpening = opening;
            installWaitingUpdate(false, true);
        } else if (PAGE_HOME.equals(page) && installTriedOpening != opening) {
            installTriedOpening = opening;
            installWaitingUpdate(false, false);
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        getWindow().getDecorView().removeCallbacks(bannerRefresh);
        super.onPause();
    }

    /** Rebuilds the home while an update is under way, so "Updating" becomes "Waiting for Play Protect". */
    private void refreshForUpdate() {
        if (!isFinishing() && !isDestroyed() && visible) {
            setContentView(buildContent());
        }
    }

    /**
     * Installs a waiting update off the main thread, then refreshes the home.
     * {@code retry}: the user tapped Try again on a stuck install, so the old session is
     * given up first.
     */
    private void installWaitingUpdate(boolean retry, boolean explicit) {
        if (!INSTALL_CHECK_RUNNING.compareAndSet(false, true)) {
            return;
        }
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                if (!SyncWorker.updateWaiting(app)) {
                    return;
                }
                if (!explicit) {
                    // The user cancelled Play Protect a moment ago: wait for the user to choose Install.
                    long last = SyncStore.evidence(app, SelfUpdater.installedVersionCode(app),
                            System.currentTimeMillis()).lastInstallAttemptAt;
                    long since = System.currentTimeMillis() - last;
                    if (last > 0 && since >= 0 && since < UpdatePolicy.HOLD_AFTER_MS) {
                        runOnUiThread(this::refreshForUpdate);
                        return;
                    }
                }
                if (retry) {
                    SyncWorker.giveUpStuckInstall(app);
                }
                SyncWorker.installOnOpen(app, () -> visible);
                runOnUiThread(this::refreshForUpdate);
            } catch (RuntimeException ignored) {
                // An update problem must never keep the user out of the app.
            } finally {
                INSTALL_CHECK_RUNNING.set(false);
            }
        }, "VoiceFlowInstallOnOpen").start();
    }

    /**
     * The update banner at the top of the home: "Updating VoiceFlow…", "Waiting for
     * Google Play Protect", or "Update ready" with Install. Null when there is none.
     */
    private View updateBanner() {
        if (SyncStore.enrolment(this) == null || !SyncWorker.updateWaiting(this)) {
            return null;
        }
        int installed = SelfUpdater.installedVersionCode(this);
        long now = System.currentTimeMillis();
        UpdateEvidence evidence = SyncStore.evidence(this, installed, now);
        UpdatePolicy.Offer offer = SyncStore.pendingOffer(this);
        String version = offer == null ? "" : " " + offer.versionName;
        UpdatePolicy.Progress progress = UpdatePolicy.progress(
                evidence.pendingState, evidence.lastInstallAttemptAt, now, true);

        LinearLayout card = Ui.card(this);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        String title;
        String detail;
        String action = null;
        if (progress == UpdatePolicy.Progress.INSTALLING) {
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(28), dp(28));
            spinnerParams.setMargins(0, 0, dp(14), 0);
            row.addView(spinner, spinnerParams);
            title = "Updating VoiceFlow…";
            detail = "If Google Play Protect asks, tap Install. The app restarts in the new version.";
        } else if (progress == UpdatePolicy.Progress.HELD) {
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            tileParams.setMargins(0, 0, dp(12), 0);
            row.addView(Ui.tile(this, R.drawable.ph_warning_circle, Ui.Tile.WARN), tileParams);
            title = "Waiting for Google Play Protect";
            detail = "Tap Install on its screen. If you closed it, try again.";
            action = "Try again";
        } else {
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            tileParams.setMargins(0, 0, dp(12), 0);
            row.addView(Ui.tile(this, R.drawable.ph_download_simple, Ui.Tile.ACCENT), tileParams);
            title = "Update ready";
            detail = "VoiceFlow" + version + " is downloaded and checked.";
            action = "Install";
        }
        labels.addView(Ui.text(this, title, 15f, true, Ui.TEXT));
        TextView detailView = Ui.text(this, detail, 13f, false, Ui.SUBTLE);
        detailView.setPadding(0, dp(3), dp(6), 0);
        labels.addView(detailView);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            TextView button = Ui.topAction(this, action, true);
            boolean retry = progress == UpdatePolicy.Progress.HELD;
            button.setOnClickListener(v -> installWaitingUpdate(retry, true));
            row.addView(button);
        }
        card.addView(row);
        card.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (progress == UpdatePolicy.Progress.INSTALLING || progress == UpdatePolicy.Progress.HELD) {
            // Look again in a while: two minutes in, "Updating" becomes the Play Protect note.
            View decor = getWindow().getDecorView();
            decor.removeCallbacks(bannerRefresh);
            decor.postDelayed(bannerRefresh, 15_000L);
        }
        return card;
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(root, dp(16), dp(4), dp(16), dp(28));
        scroll.addView(root);
        // Comfortable on the Fold's inner screen and tablets: one readable column.
        root.setLayoutParams(new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int width = getResources().getConfiguration().screenWidthDp;
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                width > 640 ? dp(600) : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        columnParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(column, columnParams);

        switch (page) {
            case PAGE_STYLES:
                buildStylesPage(column);
                break;
            case PAGE_MODELS:
                buildModelsPage(column);
                break;
            case PAGE_KEYBOARD:
                buildKeyboardPage(column);
                break;
            default:
                buildHome(column);
                break;
        }
        return scroll;
    }

    // ---------------------------------------------------------------------- home

    private void buildHome(LinearLayout column) {
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setMinimumHeight(dp(64));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        logoParams.setMargins(0, 0, dp(12), 0);
        brand.addView(Ui.tile(this, R.drawable.ph_waveform, Ui.Tile.ACCENT), logoParams);
        TextView name = Ui.text(this, "VoiceFlow", 22f, true, Ui.TEXT);
        name.setLetterSpacing(-0.02f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            name.setAccessibilityHeading(true);
        }
        brand.addView(name);
        column.addView(brand);

        View banner = updateBanner();
        if (banner != null) {
            column.addView(banner, Ui.cardParams(this, 4));
        }
        column.addView(statusCard(), Ui.cardParams(this, banner == null ? 4 : 12));

        LinearLayout main = Ui.card(this);
        String preset = Prefs.activePreset(this);
        main.addView(Ui.row(this, R.drawable.ph_sparkle, Ui.Tile.ACCENT, "Default style",
                VoiceBar.styleLabel(Prefs.labelForPreset(this, preset), Prefs.expressionForPreset(this, preset)),
                v -> showProfileDialog()));
        main.addView(Ui.divider(this, 60));
        int styleCount = Prefs.promptProfiles(this).size();
        main.addView(Ui.row(this, R.drawable.ph_sliders_horizontal, Ui.Tile.ACCENT, "Styles",
                styleCount + (styleCount == 1 ? " style" : " styles") + " · edit, add, choose what's on the bar",
                v -> openPage(PAGE_STYLES)));
        main.addView(Ui.divider(this, 60));
        main.addView(Ui.row(this, R.drawable.ph_book_open_text, Ui.Tile.INDIGO, "Word lists",
                wordSummary(), v -> startActivity(new Intent(this, FindReplaceActivity.class))));
        main.addView(Ui.divider(this, 60));
        int historyCount = Prefs.transcriptHistory(this).size();
        main.addView(Ui.row(this, R.drawable.ph_clock_counter_clockwise, Ui.Tile.OK, "History",
                historyCount == 0 ? "Nothing dictated yet" : "Last " + historyCount + (historyCount == 1 ? " dictation" : " dictations"),
                v -> startActivity(new Intent(this, HistoryActivity.class))));
        column.addView(main, Ui.cardParams(this, 12));

        LinearLayout setup = Ui.card(this);
        setup.addView(Ui.row(this, R.drawable.ph_key, Ui.Tile.NEUTRAL, "Keys and models",
                modelsSummary(), v -> openPage(PAGE_MODELS)));
        setup.addView(Ui.divider(this, 60));
        setup.addView(Ui.row(this, R.drawable.ph_keyboard, Ui.Tile.NEUTRAL, "Keyboard",
                keyboardSummary(), v -> openPage(PAGE_KEYBOARD)));
        setup.addView(Ui.divider(this, 60));
        setup.addView(Ui.row(this, R.drawable.ph_sun, Ui.Tile.NEUTRAL, "Appearance",
                "Follows your phone's light or dark setting", null));
        setup.addView(Ui.divider(this, 60));
        setup.addView(Ui.row(this, R.drawable.ph_info_fill, Ui.Tile.NEUTRAL, "Open-source credits",
                "Dictionaries, fonts and icons made by others", v -> showCredits()));
        column.addView(setup, Ui.cardParams(this, 12));

        TextView foot = Ui.text(this, "Tidy-up fixes punctuation and filler words in your chosen style.", 12f, false, Ui.SUBTLE);
        foot.setPadding(dp(4), dp(14), dp(4), 0);
        column.addView(foot);
    }

    private View statusCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(16), dp(14), dp(16), dp(4));

        boolean ready = isProviderSetupReady() && isVoiceFlowActive();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, dp(12));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        tileParams.setMargins(0, 0, dp(12), 0);
        top.addView(Ui.tile(this, ready ? R.drawable.ph_check_circle_fill : R.drawable.ph_warning_circle,
                ready ? Ui.Tile.OK : Ui.Tile.WARN), tileParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, ready ? "Ready to dictate" : setupStatus(), 15f, true, Ui.TEXT));
        TextView sync = Ui.text(this, syncSummary(), 13f, false, Ui.SUBTLE);
        sync.setPadding(0, dp(3), 0, 0);
        labels.addView(sync);
        top.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);
        card.addView(Ui.divider(this, 0));

        card.addView(keyValue("Keyboard", activeKeyboardSummary(), null,
                isVoiceFlowActive() ? null : v -> showInputMethodPicker()));
        card.addView(Ui.divider(this, 0));
        card.addView(keyValue("Version", SelfUpdater.installedVersionName(this), updateBadge(), null));
        return card;
    }

    private View keyValue(String key, String value, TextView badge, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView keyView = Ui.text(this, key, 14f, false, Ui.MUTED);
        row.addView(keyView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = Ui.text(this, value, 14f, true, Ui.TEXT);
        row.addView(valueView);
        if (badge != null) {
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.setMargins(dp(8), 0, 0, 0);
            row.addView(badge, badgeParams);
        }
        if (listener != null) {
            row.setClickable(true);
            row.setBackground(Ui.ripple(this));
            row.setOnClickListener(listener);
            valueView.setTextColor(Ui.ACCENT);
        }
        row.setContentDescription(key + ": " + value + (badge == null ? "" : ", " + badge.getText()));
        return row;
    }

    private TextView updateBadge() {
        if (SyncStore.enrolment(this) == null) {
            return null;
        }
        int installed = SelfUpdater.installedVersionCode(this);
        UpdateEvidence evidence = SyncStore.evidence(this, installed, System.currentTimeMillis());
        switch (evidence.pendingState) {
            case UpdatePolicy.STATE_DOWNLOADED:
                return Ui.badge(this, "Update ready", Ui.Tile.ACCENT);
            case UpdatePolicy.STATE_COMMITTED:
                return Ui.badge(this, "Installing", Ui.Tile.ACCENT);
            case UpdatePolicy.STATE_HELD:
            case UpdatePolicy.STATE_NEEDS_USER:
                return Ui.badge(this, "Needs a tap", Ui.Tile.WARN);
            default:
                return evidence.lastCheckAt > 0 ? Ui.badge(this, "Up to date", Ui.Tile.OK) : null;
        }
    }

    private String syncSummary() {
        if (SyncStore.enrolment(this) == null) {
            return "Updates arrive by USB install";
        }
        int installed = SelfUpdater.installedVersionCode(this);
        long checked = SyncStore.evidence(this, installed, System.currentTimeMillis()).lastCheckAt;
        if (checked <= 0) {
            return "Updates from your sync server · not checked yet";
        }
        long minutes = Math.max(0, (System.currentTimeMillis() - checked) / 60_000L);
        String ago = minutes < 1 ? "just now"
                : minutes < 60 ? minutes + " min ago"
                : minutes < 48 * 60 ? (minutes / 60) + " h ago"
                : (minutes / (24 * 60)) + " days ago";
        return "Updates from your sync server · checked " + ago;
    }

    private String wordSummary() {
        List<PhraseReplacement> words = Prefs.userPhraseReplacements(this);
        int shared = 0;
        try {
            WordLists.View view = WordLists.current(this);
            if (view.snapshot != null) {
                shared = view.snapshot.terms.size() - view.snapshot.count(WordLayer.PHONE)
                        - view.snapshot.count(WordLayer.DEFAULTS);
            }
        } catch (RuntimeException ignored) {
            // The summary falls back to this phone's own list.
        }
        String phone = words.size() + (words.size() == 1 ? " word" : " words") + " on this phone";
        if (shared > 0) {
            return shared + (shared == 1 ? " word" : " words") + " from your lists"
                    + (words.isEmpty() ? "" : ", " + phone);
        }
        if (words.isEmpty()) {
            return "Names and words to spell your way";
        }
        return phone;
    }

    private String modelsSummary() {
        String provider = Prefs.transcriptionProvider(this);
        String transform = Prefs.enableTransform(this) ? " · " + Prefs.transformModel(this) : "";
        return Prefs.providerLabel(provider) + " · " + Prefs.transcriptionModel(this) + transform;
    }

    private String keyboardSummary() {
        StringBuilder out = new StringBuilder("Layout, ");
        out.append(Prefs.chineseInputEnabled(this) ? "Chinese input, " : "");
        out.append(Prefs.translationEnabled(this) ? "translation, " : "");
        out.append("updates");
        return out.toString();
    }

    private void openPage(String target) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_PAGE, target));
    }

    // -------------------------------------------------------------------- styles

    private void buildStylesPage(LinearLayout column) {
        column.addView(Ui.header(this, "Styles", true));
        TextView intro = Ui.text(this, "How tidy-up writes. The keyboard's style chip picks one for a single message; "
                + "Make default there, or Default style here, keeps it.", 13f, false, Ui.SUBTLE);
        intro.setPadding(dp(4), 0, dp(4), dp(4));
        column.addView(intro);

        LinearLayout defaults = Ui.card(this);
        String preset = Prefs.activePreset(this);
        defaults.addView(Ui.row(this, R.drawable.ph_sparkle, Ui.Tile.ACCENT, "Default style",
                VoiceBar.styleLabel(Prefs.labelForPreset(this, preset), Prefs.expressionForPreset(this, preset)),
                v -> showProfileDialog()));
        column.addView(defaults, Ui.cardParams(this, 12));

        column.addView(Ui.sectionLabel(this, "Your styles"));
        LinearLayout list = Ui.card(this);
        List<PromptProfile> profiles = Prefs.promptProfiles(this);
        for (int i = 0; i < profiles.size(); i++) {
            PromptProfile profile = profiles.get(i);
            if (i > 0) {
                list.addView(Ui.divider(this, 60));
            }
            list.addView(styleRow(profile));
        }
        column.addView(list);

        column.addView(Ui.sectionLabel(this, "Add"));
        LinearLayout add = Ui.card(this);
        boolean first = true;
        for (PromptProfile template : Prefs.hiddenVoiceStyleTemplates(this)) {
            if (!first) {
                add.addView(Ui.divider(this, 60));
            }
            first = false;
            String detail = Prefs.isRelationshipStyle(template.id) ? "Relationship style" : "Built-in style";
            add.addView(Ui.row(this, R.drawable.ph_plus, Ui.Tile.NEUTRAL, "Add " + template.name, detail, v -> {
                Prefs.addVoiceStyleTemplate(this, template.id);
                setContentView(buildContent());
            }));
        }
        if (!first) {
            add.addView(Ui.divider(this, 60));
        }
        add.addView(Ui.row(this, R.drawable.ph_plus, Ui.Tile.ACCENT, "New style", "Write your own instructions",
                v -> showNewPromptDialog()));
        column.addView(add);
    }

    private View styleRow(PromptProfile profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        row.setClickable(true);
        row.setBackground(Ui.ripple(this));
        row.setOnClickListener(v -> openPrompt(profile.id));
        TextView icon = Ui.text(this, profile.icon.isEmpty() ? "✨" : profile.icon, 18f, false, Ui.TEXT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.rounded(this, Ui.SURFACE_ALT, 9));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, profile.name, 15f, true, Ui.TEXT));
        boolean isDefault = profile.id.equals(Prefs.activePreset(this));
        TextView detail = Ui.text(this, (isDefault ? "Default · " : "")
                + Prefs.expressionLabel(Prefs.expressionForPreset(this, profile.id)) + " · tap to edit", 13f, false, Ui.SUBTLE);
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageView chevron = new android.widget.ImageView(this);
        chevron.setImageResource(R.drawable.ph_caret_right);
        chevron.setColorFilter(Ui.SUBTLE);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.setContentDescription(profile.name + (isDefault ? ", default" : "") + ". Edit");
        return row;
    }

    // -------------------------------------------------------------------- models

    private void buildModelsPage(LinearLayout column) {
        column.addView(Ui.header(this, "Keys and models", true));

        column.addView(Ui.sectionLabel(this, "Keys"));
        LinearLayout keys = Ui.card(this);
        keys.addView(Ui.row(this, R.drawable.ph_key, Ui.Tile.NEUTRAL, "API keys", apiKeySummary(),
                v -> startActivity(new Intent(this, ApiKeysActivity.class))));
        column.addView(keys);

        column.addView(Ui.sectionLabel(this, "Voice input"));
        LinearLayout voice = Ui.card(this);
        voice.addView(Ui.row(this, R.drawable.ph_microphone, Ui.Tile.ACCENT, "Voice model",
                modelSelectionSummary(true), v -> openModelPicker(true)));
        voice.addView(Ui.divider(this, 60));
        LinearLayout offline = Ui.row(this, R.drawable.ph_download_simple, Ui.Tile.NEUTRAL, "Offline fallback",
                offlineFallbackSummary(), v -> prepareOfflineFallbackModel());
        offlineFallbackValue = offline.findViewWithTag("subtitle");
        voice.addView(offline);
        // 0.9.3: a style's own speech model (Styles > a style > Speech model) falls back once.
        voice.addView(Ui.divider(this, 60));
        CheckBox speechFallback = new CheckBox(this);
        speechFallback.setChecked(Prefs.speechFallbackToAppDefault(this));
        voice.addView(checkboxRow("If a style's speech model fails, try the app default",
                "Tries the voice model above once. Off: the dictation stops and the recording is kept to retry",
                speechFallback, () -> Prefs.setSpeechFallbackToAppDefault(this, speechFallback.isChecked())));
        column.addView(voice);

        column.addView(Ui.sectionLabel(this, "Tidy-up"));
        LinearLayout tidy = Ui.card(this);
        CheckBox transform = new CheckBox(this);
        transform.setChecked(Prefs.enableTransform(this));
        tidy.addView(checkboxRow("Tidy up dictation", "Off: your words go in exactly as transcribed",
                transform, () -> saveTransform(transform.isChecked())));
        tidy.addView(Ui.divider(this, 16));
        tidy.addView(Ui.row(this, R.drawable.ph_sparkle, Ui.Tile.ACCENT, "Tidy-up model",
                modelSelectionSummary(false), v -> openModelPicker(false)));
        column.addView(tidy);
    }

    // ------------------------------------------------------------------ keyboard

    private void buildKeyboardPage(LinearLayout column) {
        column.addView(Ui.header(this, "Keyboard", true));

        column.addView(Ui.sectionLabel(this, "Keyboard"));
        LinearLayout keyboard = Ui.card(this);
        keyboard.addView(Ui.row(this, R.drawable.ph_keyboard, Ui.Tile.ACCENT, "Active keyboard",
                activeKeyboardSummary(), v -> showInputMethodPicker()));
        keyboard.addView(Ui.divider(this, 60));
        keyboard.addView(Ui.row(this, R.drawable.ph_text_aa, Ui.Tile.NEUTRAL, "Keyboard test", "Try it in a test field",
                v -> startActivity(new Intent(this, KeyboardTestActivity.class))));
        keyboard.addView(Ui.divider(this, 60));
        keyboard.addView(Ui.row(this, R.drawable.ph_gear_six, Ui.Tile.NEUTRAL, "Android keyboard settings", "System settings",
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        keyboard.addView(Ui.divider(this, 16));
        CheckBox split = new CheckBox(this);
        split.setChecked(Prefs.splitLargeScreens(this));
        keyboard.addView(checkboxRow("Split keyboard on large screens",
                "On a tablet or a Fold's inner screen, the keys split in two for your thumbs",
                split, () -> Prefs.setSplitLargeScreens(this, split.isChecked())));
        column.addView(keyboard);

        column.addView(Ui.sectionLabel(this, "Chinese input"));
        LinearLayout chinese = Ui.card(this);
        CheckBox chineseEnabled = new CheckBox(this);
        chineseEnabled.setChecked(Prefs.chineseInputEnabled(this));
        chinese.addView(checkboxRow("Chinese input",
                "Adds a language key (EN, 中 or 九) beside 123 for pinyin typing and Chinese dictation",
                chineseEnabled, () -> {
                    Prefs.setChineseInputEnabled(this, chineseEnabled.isChecked());
                    setContentView(buildContent());
                }));
        if (Prefs.chineseInputEnabled(this)) {
            chinese.addView(Ui.divider(this, 16));
            LinearLayout layout = Ui.row(this, 0, Ui.Tile.NEUTRAL, "Chinese keyboard layout",
                    Prefs.chineseLayoutLabel(Prefs.chineseLayout(this)), v -> showChineseLayoutDialog());
            chineseLayoutValue = layout.findViewWithTag("subtitle");
            chinese.addView(layout);
            // 0.8.8: what a tap on the language key does. English is always the main keyboard.
            chinese.addView(Ui.divider(this, 16));
            chinese.addView(Ui.row(this, 0, Ui.Tile.NEUTRAL, "Language key",
                    languageKeySummary(), v -> showLanguageKeyDialog()));
            // 0.9.3: which keyboard a new field opens on.
            chinese.addView(Ui.divider(this, 16));
            chinese.addView(Ui.row(this, 0, Ui.Tile.NEUTRAL, "Open on",
                    openOnLabel(Prefs.keyboardOpenOn(this)), v -> showOpenOnDialog()));
            // 0.9.0: which engine turns pinyin into Chinese, and forgetting what it learned.
            chinese.addView(Ui.divider(this, 16));
            chinese.addView(Ui.row(this, 0, Ui.Tile.NEUTRAL, "Chinese engine",
                    chineseEngineSummary(), v -> showChineseEngineDialog()));
            chinese.addView(Ui.divider(this, 16));
            chinese.addView(Ui.row(this, 0, Ui.Tile.NEUTRAL, "Forget my typing",
                    "Clears the words and choices the Chinese engine learned from you", v -> confirmForgetTyping()));
        }
        column.addView(chinese);

        column.addView(Ui.sectionLabel(this, "Translation"));
        LinearLayout translation = Ui.card(this);
        CheckBox translationEnabled = new CheckBox(this);
        translationEnabled.setChecked(Prefs.translationEnabled(this));
        translation.addView(checkboxRow("Translate by voice",
                "Adds Translate to the keyboard's More menu", translationEnabled,
                () -> Prefs.setTranslationEnabled(this, translationEnabled.isChecked())));
        translation.addView(Ui.divider(this, 16));
        LinearLayout language = Ui.row(this, 0, Ui.Tile.NEUTRAL, "Target language",
                Prefs.translationTargetLanguage(this), v -> showTranslationLanguageDialog());
        translationLanguageValue = language.findViewWithTag("subtitle");
        translation.addView(language);
        column.addView(translation);

        column.addView(Ui.sectionLabel(this, "Recording"));
        LinearLayout recording = Ui.card(this);
        CheckBox cancelHidden = new CheckBox(this);
        cancelHidden.setChecked(Prefs.cancelRecordingWhenHidden(this));
        recording.addView(checkboxRow("Cancel hidden recordings",
                "Stop and discard a recording when the keyboard closes or the app changes",
                cancelHidden, () -> Prefs.setCancelRecordingWhenHidden(this, cancelHidden.isChecked())));
        column.addView(recording);

        column.addView(Ui.sectionLabel(this, "Updates"));
        LinearLayout updates = Ui.card(this);
        CheckBox mobileData = new CheckBox(this);
        mobileData.setChecked(Prefs.updatesOnMobileData(this));
        updates.addView(checkboxRow("Allow updates on mobile data",
                "Off: updates download on Wi-Fi only. When one is ready you get a quiet notice; it installs when you open VoiceFlow.",
                mobileData, () -> Prefs.setUpdatesOnMobileData(this, mobileData.isChecked())));
        column.addView(updates);
    }

    // ------------------------------------------------------------------ shared

    private View checkboxRow(String title, String subtitle, CheckBox checkBox, Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(10), dp(8), dp(10));
        row.setClickable(true);
        row.setBackground(Ui.ripple(this));
        row.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
            onChanged.run();
        });
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, title, 15f, true, Ui.TEXT));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = Ui.text(this, subtitle, 13f, false, Ui.SUBTLE);
            detail.setPadding(0, dp(3), dp(8), 0);
            labels.addView(detail);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        checkBox.setContentDescription(title);
        checkBox.setOnClickListener(v -> onChanged.run());
        checkBox.setMinimumWidth(dp(48));
        checkBox.setMinimumHeight(dp(48));
        row.addView(checkBox);
        return row;
    }

    private String setupStatus() {
        if (!Prefs.hasApiKeyForProvider(this, Prefs.transcriptionProvider(this))) {
            return Prefs.providerLabel(Prefs.transcriptionProvider(this)) + " key needed for voice input";
        }
        if (Prefs.enableTransform(this) && !Prefs.hasApiKeyForProvider(this, Prefs.transformProvider(this))) {
            return Prefs.providerLabel(Prefs.transformProvider(this)) + " key needed for tidy-up";
        }
        if (!isVoiceFlowActive()) {
            return "Keyboard installed, not active";
        }
        return "Ready to dictate";
    }

    private boolean isProviderSetupReady() {
        return Prefs.hasApiKeyForProvider(this, Prefs.transcriptionProvider(this))
                && (!Prefs.enableTransform(this) || Prefs.hasApiKeyForProvider(this, Prefs.transformProvider(this)));
    }

    private String modelSelectionSummary(boolean transcription) {
        String provider = transcription ? Prefs.transcriptionProvider(this) : Prefs.transformProvider(this);
        String model = transcription ? Prefs.transcriptionModel(this) : Prefs.transformModel(this);
        return Prefs.providerLabel(provider) + " · " + model;
    }

    private void openModelPicker(boolean transcription) {
        Intent intent = new Intent(this, ModelPickerActivity.class);
        intent.putExtra(ModelPickerActivity.EXTRA_MODE, transcription ? ModelPickerActivity.MODE_TRANSCRIPTION : ModelPickerActivity.MODE_TRANSFORM);
        startActivity(intent);
    }

    /**
     * The attributions the bundled data's licences ask for (0.8.9: the Wanxiang
     * pinyin dictionary, CC BY 4.0), from assets/licenses/CREDITS.txt.
     */
    private void showCredits() {
        String text;
        try {
            // 0.9.0: then the full licence texts of the Rime engine and its libraries.
            text = readAsset("licenses/CREDITS.txt") + "\n\n" + readAsset("licenses/Rime-NOTICES.txt");
        } catch (java.io.IOException e) {
            text = "Credits could not be read.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Open-source credits")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .show();
    }

    private String readAsset(String path) throws java.io.IOException {
        try (java.io.InputStream in = getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void showProfileDialog() {
        String[] values = Prefs.selectablePresetValues(this);
        String[] labels = Prefs.labelsForPresets(this, values);
        new AlertDialog.Builder(this)
                .setTitle("Default style")
                .setItems(labels, (dialog, which) -> {
                    saveSettings(Prefs.enableTransform(this), values[which]);
                    setContentView(buildContent());
                })
                .show();
    }

    private void saveTransform(boolean enabled) {
        saveSettings(enabled, Prefs.activePreset(this));
    }

    private void saveSettings(boolean transformEnabled, String preset) {
        Prefs.save(
                this,
                Prefs.openAiApiKey(this),
                Prefs.transcriptionProvider(this),
                Prefs.transformProvider(this),
                Prefs.transcriptionModel(this),
                Prefs.transformModel(this),
                transformEnabled,
                preset
        );
    }

    private void showChineseLayoutDialog() {
        String[] values = {Prefs.CHINESE_LAYOUT_QWERTY, Prefs.CHINESE_LAYOUT_KEYPAD};
        String[] labels = new String[values.length];
        int checked = 0;
        String selected = Prefs.chineseLayout(this);
        for (int i = 0; i < values.length; i++) {
            labels[i] = Prefs.chineseLayoutLabel(values[i]);
            if (values[i].equals(selected)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Chinese keyboard layout")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setChineseLayout(this, values[which]);
                    dialog.dismiss();
                    // The language key's summary names this layout too.
                    setContentView(buildContent());
                })
                .show();
    }

    /** "Rime", or why Classic is serving instead (0.9.0). */
    private String chineseEngineSummary() {
        String engine = Prefs.chineseEngine(this);
        if (Prefs.CHINESE_ENGINE_CLASSIC.equals(engine)) {
            return "Classic";
        }
        RimeBreaker breaker = RimeAndroid.breaker(this);
        if (breaker.tripped()) {
            return "Rime is off after repeated problems; Classic is in use. Choose Rime to try it again.";
        }
        return "Rime (Classic stands in while it starts)";
    }

    private void showChineseEngineDialog() {
        String[] values = {Prefs.CHINESE_ENGINE_RIME, Prefs.CHINESE_ENGINE_CLASSIC};
        String[] labels = {"Rime: better predictions, learns from you", "Classic: the earlier engine"};
        String selected = Prefs.chineseEngine(this);
        int checked = Prefs.CHINESE_ENGINE_CLASSIC.equals(selected) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Chinese engine")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setChineseEngine(this, values[which]);
                    if (Prefs.CHINESE_ENGINE_RIME.equals(values[which])) {
                        // Choosing Rime is how she turns it back on after the breaker tripped.
                        RimeAndroid.engine(this).reenable();
                    }
                    dialog.dismiss();
                    setContentView(buildContent());
                })
                .show();
    }

    private void confirmForgetTyping() {
        new AlertDialog.Builder(this)
                .setTitle("Forget my typing?")
                .setMessage("The Chinese engine forgets the words and choices it learned from your typing. "
                        + "The built-in dictionary stays.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> RimeAndroid.engine(this).forget(
                        RimeAndroid.userDir(this),
                        ok -> Toast.makeText(this, ok ? "Forgotten" : "Could not clear everything; try again",
                                Toast.LENGTH_SHORT).show()))
                .show();
    }

    private String languageKeySummary() {
        return LanguageKey.describe(Prefs.languageKeyBehaviour(this),
                InputMode.chineseFor(Prefs.chineseLayout(this)));
    }

    private static String openOnLabel(String value) {
        return KeyboardOpening.OPEN_ENGLISH.equals(value) ? "Always English" : "Last used keyboard";
    }

    private void showOpenOnDialog() {
        String[] values = {KeyboardOpening.OPEN_LAST_USED, KeyboardOpening.OPEN_ENGLISH};
        String[] labels = {"Last used keyboard (default)", "Always English"};
        int checked = KeyboardOpening.OPEN_ENGLISH.equals(Prefs.keyboardOpenOn(this)) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Open on")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setKeyboardOpenOn(this, values[which]);
                    dialog.dismiss();
                    setContentView(buildContent());
                })
                .show();
    }

    private void showLanguageKeyDialog() {
        LanguageKey.Behaviour[] values = LanguageKey.Behaviour.values();
        InputMode secondary = InputMode.chineseFor(Prefs.chineseLayout(this));
        String[] labels = new String[values.length];
        int checked = 0;
        LanguageKey.Behaviour selected = Prefs.languageKeyBehaviour(this);
        for (int i = 0; i < values.length; i++) {
            labels[i] = LanguageKey.describe(values[i], secondary)
                    + (values[i] == LanguageKey.Behaviour.TOGGLE ? " (default)" : "");
            if (values[i] == selected) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Language key")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setLanguageKeyBehaviour(this, values[which]);
                    dialog.dismiss();
                    setContentView(buildContent());
                })
                .show();
    }

    private void showTranslationLanguageDialog() {
        String[] languages = Prefs.translationLanguages();
        String selected = Prefs.translationTargetLanguage(this);
        int checked = 0;
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(selected)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Target language")
                .setSingleChoiceItems(languages, checked, (dialog, which) -> {
                    Prefs.setTranslationTargetLanguage(this, languages[which]);
                    if (translationLanguageValue != null) {
                        translationLanguageValue.setText(languages[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openPrompt(String id) {
        Intent intent = new Intent(this, PromptEditorActivity.class);
        intent.putExtra(PromptEditorActivity.EXTRA_PROMPT_ID, id);
        startActivity(intent);
    }

    private void showNewPromptDialog() {
        EditText nameInput = new EditText(this);
        nameInput.setHint("Style name");
        nameInput.setTextColor(Ui.TEXT);
        nameInput.setHintTextColor(Ui.SUBTLE);
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(nameInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("New style")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String id = Prefs.addPromptProfile(this, nameInput.getText().toString());
                    openPrompt(id);
                })
                .show();
    }

    private String apiKeySummary() {
        int count = Prefs.savedApiKeyCount(this);
        if (count == 0) {
            return "OpenAI not set";
        }
        if (count == 1 && Prefs.hasOpenAiApiKey(this)) {
            return "OpenAI connected";
        }
        return count + " keys saved";
    }

    private String offlineFallbackSummary() {
        if (OfflineParakeetClient.isModelReady(this)) {
            return "Parakeet ready";
        }
        if (OfflineVoskClient.isModelReady(this)) {
            return "Vosk ready";
        }
        return downloadingOfflineModel ? "Downloading..." : "Download a compact model for no signal";
    }

    private void prepareOfflineFallbackModel() {
        if (OfflineParakeetClient.isModelReady(this) || OfflineVoskClient.isModelReady(this)) {
            Toast.makeText(this, offlineFallbackSummary(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloadingOfflineModel) {
            return;
        }
        downloadingOfflineModel = true;
        if (offlineFallbackValue != null) {
            offlineFallbackValue.setText("Downloading...");
        }
        new Thread(() -> {
            try {
                OfflineVoskClient.ensureModel(getApplicationContext());
                runOnUiThread(() -> {
                    downloadingOfflineModel = false;
                    if (offlineFallbackValue != null) {
                        offlineFallbackValue.setText(offlineFallbackSummary());
                    }
                    Toast.makeText(this, "Offline fallback is ready", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Download failed" : e.getMessage();
                runOnUiThread(() -> {
                    downloadingOfflineModel = false;
                    if (offlineFallbackValue != null) {
                        offlineFallbackValue.setText(offlineFallbackSummary());
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Offline fallback")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "VoiceFlowOfflineModelDownload").start();
    }

    private String activeKeyboardSummary() {
        return isVoiceFlowActive() ? "VoiceFlow" : "Choose keyboard";
    }

    private boolean isVoiceFlowActive() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.contains("com.voiceflowkeyboard.ime/.VoiceFlowKeyboardService");
    }

    private void showInputMethodPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showInputMethodPicker();
        }
    }

    private void requestAudioPermission() {
        java.util.List<String> wanted = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO);
        }
        // An enrolled phone updates itself; if Android ever asks for a tap, a
        // notification is how it says so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && SyncStore.enrolment(this) != null
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), 10);
        }
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
