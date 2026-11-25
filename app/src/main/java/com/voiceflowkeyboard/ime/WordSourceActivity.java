package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Word lists source: where this phone reads the shared word lists from.
 * <ul>
 *   <li>This phone only: its own list, as before.</li>
 *   <li>Sync server: the vf-sync gateway it was enrolled with (enrolment stays adb-only).</li>
 *   <li>Private GitHub repository (docs/GITHUB-SYNC.md): repository, branch, the user's
 *       name and a read-only fine-grained token, sealed in the Android Keystore.</li>
 * </ul>
 * The gateway keeps delivering app updates and the phone's status whichever source is
 * chosen. Nothing here writes to GitHub.
 */
public class WordSourceActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private RadioGroup choice;
    private RadioButton none;
    private RadioButton gateway;
    private RadioButton github;
    private LinearLayout githubSection;
    private EditText repo;
    private EditText branch;
    private EditText user;
    private EditText token;
    private TextView testResult;

    /** One line for the Word lists screen. */
    static String summary(Context context) {
        switch (SyncStore.wordSource(context)) {
            case SyncStore.SOURCE_NONE:
                return "this phone only";
            case SyncStore.SOURCE_GITHUB: {
                String[] fields = SyncStore.gitHubFields(context);
                return SyncStore.gitHub(context) == null ? "GitHub (not finished)" : "GitHub, " + fields[0];
            }
            default:
                return SyncStore.enrolment(context) == null ? "this phone only" : "your sync server";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        setTitle("Word lists source");
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Ui.BACKGROUND);
        screen.addView(topBar());

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(96));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = Ui.text(this, "Shared word lists add your household's and your own words to dictation. "
                + "Words you add on this phone stay on this phone either way.", 14f, false, Ui.MUTED);
        note.setPadding(0, 0, 0, dp(8));
        root.addView(note);

        root.addView(label("Where they come from"));
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(12), dp(6), dp(12), dp(6));
        choice = new RadioGroup(this);
        none = radio("This phone only");
        Enrolment enrolled = SyncStore.enrolment(this);
        gateway = radio(enrolled == null ? "Sync server (not set up on this phone)" : "Sync server (" + host(enrolled.gateway) + ")");
        gateway.setEnabled(enrolled != null);
        github = radio("Private GitHub repository");
        choice.addView(none);
        choice.addView(gateway);
        choice.addView(github);
        card.addView(choice);
        root.addView(card, Ui.cardParams(this, 0));

        String source = SyncStore.wordSource(this);
        if (SyncStore.SOURCE_GITHUB.equals(source)) {
            github.setChecked(true);
        } else if (SyncStore.SOURCE_NONE.equals(source) || enrolled == null) {
            none.setChecked(true);
        } else {
            gateway.setChecked(true);
        }

        githubSection = new LinearLayout(this);
        githubSection.setOrientation(LinearLayout.VERTICAL);
        String[] saved = SyncStore.gitHubFields(this);
        githubSection.addView(label("GitHub repository"));
        LinearLayout fields = Ui.card(this);
        fields.setPadding(dp(16), dp(8), dp(16), dp(12));
        repo = input("your-name/voiceflow-words", saved[0], false);
        branch = input("main", saved[1].isEmpty() ? "main" : saved[1], false);
        user = input("your name, lower case", saved[2], false);
        token = input(saved[3].isEmpty() ? "github_pat_..." : "Stored on this phone (paste a new one to replace it)", "", true);
        fields.addView(field("Repository", repo));
        fields.addView(field("Branch", branch));
        fields.addView(field("Your name", user));
        fields.addView(field("Access token", token));
        TextView help = Ui.text(this, "Make a fine-grained token for this one repository with Contents: Read-only. "
                + "The phone only reads; it is kept encrypted on this phone. Steps: docs/GITHUB-SYNC.md in the VoiceFlow repository.",
                12f, false, Ui.SUBTLE);
        help.setPadding(0, dp(8), 0, 0);
        fields.addView(help);
        githubSection.addView(fields, Ui.cardParams(this, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);
        TextView test = Ui.topAction(this, "Test connection", false);
        test.setOnClickListener(v -> testConnection());
        actions.addView(test);
        if (!saved[3].isEmpty()) {
            TextView forget = Ui.topAction(this, "Forget GitHub", false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(16), 0, 0, 0);
            forget.setOnClickListener(v -> forget());
            actions.addView(forget, params);
        }
        githubSection.addView(actions);
        testResult = Ui.text(this, "", 13f, false, Ui.MUTED);
        testResult.setPadding(dp(4), dp(10), dp(4), 0);
        githubSection.addView(testResult);
        root.addView(githubSection);

        choice.setOnCheckedChangeListener((group, id) -> showGitHub());
        showGitHub();
        return screen;
    }

    private void showGitHub() {
        githubSection.setVisibility(github.isChecked() ? View.VISIBLE : View.GONE);
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(bar, dp(16), dp(10), dp(16), dp(10));
        TextView back = Ui.topAction(this, "Back", false);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.text(this, "Word lists source", 18, true, Ui.TEXT);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(12), 0, dp(12), 0);
        bar.addView(title, titleParams);
        TextView save = Ui.topAction(this, "Save", true);
        save.setOnClickListener(v -> save());
        bar.addView(save);
        return bar;
    }

    private void save() {
        try {
            if (github.isChecked()) {
                SyncStore.saveGitHub(this, text(repo), text(branch), text(user), text(token));
            } else if (gateway.isChecked()) {
                SyncStore.chooseWordSource(this, SyncStore.SOURCE_GATEWAY);
            } else {
                SyncStore.chooseWordSource(this, SyncStore.SOURCE_NONE);
            }
        } catch (IllegalArgumentException e) {
            testResult.setText(e.getMessage());
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save the settings on this phone.", Toast.LENGTH_LONG).show();
            return;
        }
        token.setText("");
        WordLists.invalidate();
        SyncScheduler.kick(this);
        Toast.makeText(this, "Saved. Your word lists are checked now.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void forget() {
        SyncStore.forgetGitHub(this);
        WordLists.invalidate();
        Toast.makeText(this, "GitHub settings and token removed from this phone.", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Reads the repository on a background thread; writes nothing. */
    private void testConnection() {
        String[] parts = GitHubClient.parseRepo(text(repo));
        String b = text(branch);
        String u = text(user).toLowerCase(java.util.Locale.ROOT);
        if (parts == null) {
            testResult.setText("Enter the repository as owner/name.");
            return;
        }
        if (!GitHubClient.validBranch(b)) {
            testResult.setText("Enter the branch name (usually main).");
            return;
        }
        if (!Enrolment.validUser(u)) {
            testResult.setText("Enter your name: lower-case letters, digits, - or _.");
            return;
        }
        String typed = text(token);
        testResult.setText("Testing...");
        Context app = getApplicationContext();
        new Thread(() -> {
            String message;
            try {
                String t = typed;
                if (t.isEmpty()) {
                    SyncStore.GitHubConfig config = SyncStore.gitHub(app);
                    t = config == null ? "" : config.token();
                }
                if (!GitHubClient.validToken(t)) {
                    message = t.isEmpty() ? "Paste the access token first." : "That doesn't look like a GitHub token (it starts with github_pat_).";
                } else {
                    GitHubClient.TestResult result = new GitHubClient(parts[0], parts[1], b, u, SyncStore.deviceId(app), t,
                            SyncStore.gitHubWait(app)).test();
                    StringBuilder out = new StringBuilder(result.message);
                    for (String warning : result.warnings) {
                        out.append("\n").append(warning);
                    }
                    if (result.ok && !result.users.isEmpty()) {
                        out.append("\nNames in it: ").append(String.join(", ", result.users));
                    }
                    message = out.toString();
                }
            } catch (Exception e) {
                message = "The token saved on this phone couldn't be read; paste it again.";
            }
            String shown = message;
            main.post(() -> {
                if (!isFinishing()) {
                    testResult.setText(shown);
                }
            });
        }, "VoiceFlowGitHubTest").start();
    }

    private static String host(String origin) {
        return origin.replaceFirst("^https://", "");
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private TextView label(String title) {
        TextView label = Ui.text(this, title, 12, true, Ui.MUTED);
        label.setAllCaps(true);
        label.setLetterSpacing(0.04f);
        label.setPadding(0, dp(18), 0, dp(7));
        return label;
    }

    private RadioButton radio(String title) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(title);
        button.setTextColor(Ui.TEXT);
        button.setTextSize(15);
        button.setTypeface(Fonts.regular(this));
        button.setMinHeight(dp(48));
        return button;
    }

    private EditText input(String hint, String value, boolean secret) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.SUBTLE);
        input.setSingleLine(true);
        input.setTypeface(Fonts.regular(this));
        input.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return input;
    }

    private View field(String title, EditText input) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.setPadding(0, dp(8), 0, 0);
        field.addView(Ui.text(this, title, 13, true, Ui.MUTED));
        field.addView(input);
        return field;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
