package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
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

import java.util.List;

/**
 * Word lists source: where this phone reads the shared word lists from.
 * <ul>
 *   <li>This phone only: its own list, as before.</li>
 *   <li>Sync server: the vf-sync gateway it was enrolled with (enrolment stays adb-only).</li>
 *   <li>Private GitHub repository (docs/GITHUB-SYNC.md): <b>Connect GitHub</b> signs the
 *       phone in to the VoiceFlow Word Lists GitHub App (the same App computers use) with a
 *       code (Copy code, Open GitHub), then lists the repositories the App is installed on. A
 *       pasted token stays as an Advanced choice. Either is sealed in the Android Keystore.</li>
 * </ul>
 * The gateway keeps delivering app updates and the phone's status whichever source is
 * chosen. Nothing here writes to GitHub. The wait for the code runs on its own thread
 * ({@link GitHubAuth}); this screen only reads how it is going, so it can be closed and
 * reopened (for example after the browser) without losing the attempt.
 */
public class WordSourceActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private RadioGroup choice;
    private RadioButton none;
    private RadioButton gateway;
    private RadioButton github;
    private LinearLayout githubSection;
    private LinearLayout connectBox;
    private LinearLayout repoList;
    private LinearLayout advanced;
    private TextView advancedToggle;
    private EditText repo;
    private EditText branch;
    private EditText user;
    private EditText token;
    private TextView testResult;
    private TextView forget;
    private final Runnable watch = this::showConnect;
    /** The Connect attempt whose repositories were already listed here. */
    private long reposShownFor = -1;

    /** One line for the Word lists screen. */
    static String summary(Context context) {
        switch (SyncStore.wordSource(context)) {
            case SyncStore.SOURCE_NONE:
                return "this phone only";
            case SyncStore.SOURCE_GITHUB: {
                String[] fields = SyncStore.gitHubFields(context);
                if (SyncStore.gitHub(context) == null) {
                    return "GitHub (not finished)";
                }
                return "reconnect".equals(GitHubCredentials.kind(context, System.currentTimeMillis()))
                        ? "GitHub, connect again" : "GitHub, " + fields[0];
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

    @Override
    protected void onResume() {
        super.onResume();
        showConnect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(watch);
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

        githubSection.addView(label("GitHub"));
        connectBox = Ui.card(this);
        connectBox.setPadding(dp(16), dp(10), dp(16), dp(12));
        githubSection.addView(connectBox, Ui.cardParams(this, 0));

        githubSection.addView(label("Repository"));
        LinearLayout fields = Ui.card(this);
        fields.setPadding(dp(16), dp(8), dp(16), dp(12));
        repo = input("your-name/voiceflow-words", saved[0], false);
        branch = input("main", saved[1].isEmpty() ? "main" : saved[1], false);
        user = input("your name, lower case", saved[2], false);
        fields.addView(field("Repository", repo));
        TextView choose = Ui.topAction(this, "Choose from GitHub", false);
        choose.setPadding(0, dp(8), 0, dp(4));
        choose.setOnClickListener(v -> loadRepos());
        fields.addView(choose);
        repoList = new LinearLayout(this);
        repoList.setOrientation(LinearLayout.VERTICAL);
        fields.addView(repoList);
        fields.addView(field("Branch", branch));
        fields.addView(field("Your name", user));

        advancedToggle = Ui.text(this, "", 13f, true, Ui.MUTED);
        advancedToggle.setPadding(0, dp(14), 0, dp(4));
        advancedToggle.setOnClickListener(v -> showAdvanced(advanced.getVisibility() != View.VISIBLE));
        fields.addView(advancedToggle);
        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        token = input("github_pat_...", "", true);
        advanced.addView(field("Access token", token));
        TextView help = Ui.text(this, "A fine-grained token for this one repository with Contents: Read-only "
                + "(this phone only reads the lists). "
                + "Saving it replaces a GitHub connection on this phone. It is kept encrypted on this phone. "
                + "Steps: docs/GITHUB-SYNC.md in the VoiceFlow repository.", 12f, false, Ui.SUBTLE);
        help.setPadding(0, dp(8), 0, 0);
        advanced.addView(help);
        fields.addView(advanced);
        githubSection.addView(fields, Ui.cardParams(this, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);
        TextView test = Ui.topAction(this, "Test connection", false);
        test.setOnClickListener(v -> testConnection());
        actions.addView(test);
        forget = Ui.topAction(this, "Forget GitHub", false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(16), 0, 0, 0);
        forget.setOnClickListener(v -> forget());
        actions.addView(forget, params);
        githubSection.addView(actions);
        testResult = Ui.text(this, "", 13f, false, Ui.MUTED);
        testResult.setPadding(dp(4), dp(10), dp(4), 0);
        githubSection.addView(testResult);
        root.addView(githubSection);

        showAdvanced("pat".equals(kind()));
        choice.setOnCheckedChangeListener((group, id) -> showGitHub());
        showGitHub();
        showConnect();
        return screen;
    }

    private String kind() {
        return GitHubCredentials.kind(this, System.currentTimeMillis());
    }

    private void showGitHub() {
        githubSection.setVisibility(github.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void showAdvanced(boolean open) {
        advanced.setVisibility(open ? View.VISIBLE : View.GONE);
        advancedToggle.setText((open ? "▾ " : "▸ ") + "Advanced: use an access token instead");
    }

    /** The GitHub card: connected, a code to enter, or the Connect button. Re-run while a code waits. */
    private void showConnect() {
        if (connectBox == null || isFinishing()) {
            return;
        }
        main.removeCallbacks(watch);
        connectBox.removeAllViews();
        String kind = kind();
        forget.setVisibility(kind.isEmpty() && SyncStore.gitHub(this) == null ? View.GONE : View.VISIBLE);
        GitHubAuth.Attempt attempt = GitHubAuth.current();
        if (attempt != null && "waiting".equals(attempt.state)) {
            connectBox.addView(Ui.text(this, "Enter this code on GitHub, then approve VoiceFlow Word Lists:",
                    13f, false, Ui.MUTED));
            TextView code = Ui.text(this, attempt.userCode, 30f, true, Ui.TEXT);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            code.setLetterSpacing(0.12f);
            code.setTextIsSelectable(true);
            code.setPadding(0, dp(8), 0, dp(8));
            connectBox.addView(code);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            TextView copy = Ui.topAction(this, "Copy code", false);
            copy.setOnClickListener(v -> copyCode(attempt.userCode));
            TextView open = Ui.topAction(this, "Open GitHub", true);
            open.setOnClickListener(v -> openLink(attempt.verificationUri));
            TextView cancel = Ui.topAction(this, "Cancel", false);
            cancel.setOnClickListener(v -> {
                GitHubAuth.cancel(attempt.id);
                showConnect();
            });
            buttons.addView(copy);
            buttons.addView(open, spaced());
            buttons.addView(cancel, spaced());
            connectBox.addView(buttons);
            TextView wait = Ui.text(this, "Waiting for GitHub... This updates by itself. The code works for 15 minutes.",
                    12f, false, Ui.SUBTLE);
            wait.setPadding(0, dp(8), 0, 0);
            connectBox.addView(wait);
            main.postDelayed(watch, 1000);
            return;
        }
        if (attempt != null && "connected".equals(attempt.state) && "app".equals(kind) && reposShownFor != attempt.id) {
            // Just connected: show the repositories once.
            reposShownFor = attempt.id;
            loadRepos();
        }
        if ("app".equals(kind)) {
            connectBox.addView(Ui.text(this, "Connected to GitHub", 15f, true, Ui.TEXT));
            connectBox.addView(Ui.text(this, "Through the VoiceFlow Word Lists app: nothing to copy or renew. "
                    + "This phone only reads the lists. Forget GitHub disconnects this phone.", 12f, false, Ui.SUBTLE));
            TextView manage = Ui.topAction(this, "Manage on GitHub", false);
            manage.setPadding(0, dp(8), 0, 0);
            manage.setOnClickListener(v -> openLink(GitHubApp.AUTHORIZATIONS_URL));
            connectBox.addView(manage);
            return;
        }
        if ("reconnect".equals(kind)) {
            connectBox.addView(Ui.text(this, "VoiceFlow's connection to GitHub has ended. Connect again to keep your word lists up to date.",
                    13f, false, Ui.TEXT));
        }
        TextView connect = Ui.topAction(this, "reconnect".equals(kind) ? "Connect GitHub again" : "Connect GitHub", true);
        connect.setPadding(0, dp(6), 0, dp(6));
        connect.setOnClickListener(v -> startConnect());
        connectBox.addView(connect);
        String why = attempt != null && attempt.message != null && !attempt.message.isEmpty()
                && !"connected".equals(attempt.state) ? attempt.message : null;
        connectBox.addView(Ui.text(this, why != null ? why
                : "Signs this phone in to GitHub with the VoiceFlow Word Lists app, the one your computers use. No token to make or paste.",
                12f, false, why != null ? Ui.TEXT : Ui.SUBTLE));
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(16), 0, 0, 0);
        return params;
    }

    /** Asks GitHub for a code, then waits for it on its own thread (the device code never leaves it). */
    private void startConnect() {
        Context app = getApplicationContext();
        testResult.setText("Asking GitHub for a code...");
        new Thread(() -> {
            String error = null;
            try {
                GitHubAuth.DeviceCode code = GitHubAuth.requestCode(GitHubApp.CLIENT_ID);
                GitHubAuth.Attempt attempt = GitHubAuth.begin(code, System.currentTimeMillis());
                new Thread(() -> {
                    GitHubAuth.Outcome outcome = GitHubAuth.waitForUser(GitHubApp.CLIENT_ID, code, attempt.cancelled,
                            GitHubAuth.realClock());
                    GitHubAuth.finish(attempt.id, outcome, tokens -> GitHubCredentials.connect(app, tokens));
                    if (outcome.end == GitHubAuth.End.CONNECTED) {
                        WordLists.invalidate();
                        SyncScheduler.kick(app);
                    }
                }, "VoiceFlowGitHubConnect").start();
            } catch (GitHubAuth.AuthException e) {
                error = e.getMessage();
            }
            String shown = error;
            main.post(() -> {
                if (!isFinishing()) {
                    testResult.setText(shown == null ? "" : shown);
                    showConnect();
                }
            });
        }, "VoiceFlowGitHubCode").start();
    }

    private void copyCode(String code) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", code));
            Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens one of GitHub's own pages in the browser. */
    private void openLink(String url) {
        if (!url.startsWith("https://github.com/")) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No browser to open " + url, Toast.LENGTH_LONG).show();
        }
    }

    /** The repositories the connected App reaches, as a list to tap. */
    private void loadRepos() {
        Context app = getApplicationContext();
        repoList.removeAllViews();
        repoList.addView(Ui.text(this, "Loading your repositories...", 13f, false, Ui.MUTED));
        new Thread(() -> {
            List<GitHubAuth.RepoChoice> repos = null;
            String message = null;
            try {
                GitHubCredentials.Access access = GitHubCredentials.access(app, System.currentTimeMillis());
                if (!access.app) {
                    message = "Choosing from a list needs Connect GitHub; with an access token, type the repository.";
                } else {
                    try {
                        repos = GitHubAuth.listRepos(access.token);
                    } catch (GitHubAuth.Unauthorized e) {
                        GitHubCredentials.Access next = GitHubCredentials.afterUnauthorized(app, access, System.currentTimeMillis());
                        try {
                            repos = GitHubAuth.listRepos(next.token);
                        } catch (GitHubAuth.Unauthorized again) {
                            GitHubCredentials.refusedAgain(app, next);
                            message = GitHubCredentials.RECONNECT;
                        }
                    }
                }
            } catch (GitHubCredentials.Problem e) {
                message = e.getMessage();
            } catch (java.io.IOException e) {
                message = e.getMessage() == null || e.getMessage().startsWith("github-") || e.getMessage().equals("response-too-large")
                        ? "Can't reach GitHub. Check the internet connection." : e.getMessage();
            }
            List<GitHubAuth.RepoChoice> found = repos;
            String shown = message;
            main.post(() -> {
                if (!isFinishing()) {
                    showRepos(found, shown);
                }
            });
        }, "VoiceFlowGitHubRepos").start();
    }

    private void showRepos(List<GitHubAuth.RepoChoice> repos, String message) {
        repoList.removeAllViews();
        if (message != null) {
            repoList.addView(Ui.text(this, message, 13f, false, Ui.TEXT));
            showConnect();
            return;
        }
        if (repos.isEmpty()) {
            repoList.addView(Ui.text(this, "VoiceFlow Word Lists isn't installed on any of your repositories yet. "
                    + "Install it on your word-list repository (choose Only select repositories), then check again. "
                    + "Someone else's repository: its owner installs the app and adds you.", 13f, false, Ui.TEXT));
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, dp(6), 0, dp(6));
            TextView install = Ui.topAction(this, "Install on GitHub", true);
            install.setOnClickListener(v -> openLink(GitHubApp.installUrl()));
            TextView again = Ui.topAction(this, "Check again", false);
            again.setOnClickListener(v -> loadRepos());
            buttons.addView(install);
            buttons.addView(again, spaced());
            repoList.addView(buttons);
            return;
        }
        for (GitHubAuth.RepoChoice r : repos) {
            TextView row = Ui.text(this, r.fullName + (r.isPrivate ? "" : "   (public)"), 15f, false, Ui.TEXT);
            row.setMinHeight(dp(44));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.ripple(this));
            row.setOnClickListener(v -> {
                repo.setText(r.fullName);
                branch.setText(r.defaultBranch.isEmpty() ? "main" : r.defaultBranch);
                repoList.removeAllViews();
                testResult.setText(r.isPrivate ? "" : "This repository is public: anyone can read its word lists. Make it private on GitHub.");
            });
            repoList.addView(row);
        }
        if (repos.size() == 1 && text(repo).isEmpty()) {
            repo.setText(repos.get(0).fullName);
            branch.setText(repos.get(0).defaultBranch);
        }
        TextView more = Ui.topAction(this, "Not in the list? Add it on GitHub", false);
        more.setPadding(0, dp(6), 0, 0);
        more.setOnClickListener(v -> openLink(GitHubApp.installUrl()));
        repoList.addView(more);
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
        Toast.makeText(this, "GitHub settings and sign-in removed from this phone.", Toast.LENGTH_SHORT).show();
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
                if (!typed.isEmpty() && !GitHubClient.validToken(typed)) {
                    message = "That doesn't look like a GitHub token (it starts with github_pat_).";
                } else {
                    // A typed token is used for this test only; otherwise the saved sign-in.
                    GitHubCredentials.Access access = typed.isEmpty()
                            ? GitHubCredentials.access(app, System.currentTimeMillis())
                            : new GitHubCredentials.Access(typed, false, -1);
                    GitHubClient.TestResult result = test(app, parts, b, u, access.token);
                    if (result.authRefused && access.app) {
                        GitHubCredentials.Access next = GitHubCredentials.afterUnauthorized(app, access, System.currentTimeMillis());
                        result = test(app, parts, b, u, next.token);
                        if (result.authRefused) {
                            GitHubCredentials.refusedAgain(app, next);
                            result.message = GitHubCredentials.RECONNECT;
                        }
                    }
                    StringBuilder out = new StringBuilder(result.message);
                    for (String warning : result.warnings) {
                        out.append("\n").append(warning);
                    }
                    if (result.ok && !result.users.isEmpty()) {
                        out.append("\nNames in it: ").append(String.join(", ", result.users));
                    }
                    message = out.toString();
                }
            } catch (GitHubCredentials.Problem e) {
                message = e.getMessage();
            } catch (Exception e) {
                message = "The sign-in saved on this phone couldn't be read; connect GitHub again.";
            }
            String shown = message;
            main.post(() -> {
                if (!isFinishing()) {
                    testResult.setText(shown);
                    showConnect();
                }
            });
        }, "VoiceFlowGitHubTest").start();
    }

    private static GitHubClient.TestResult test(Context app, String[] parts, String b, String u, String t) {
        return new GitHubClient(parts[0], parts[1], b, u, SyncStore.deviceId(app), t, SyncStore.gitHubWait(app)).test();
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
