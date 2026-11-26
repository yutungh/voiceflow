package com.voiceflowkeyboard.ime;

/**
 * The GitHub App phones connect to (docs/GITHUB-SYNC.md): "VoiceFlow Word Lists
 * (read-only)", with Contents: read-only, so a lost phone's sign-in can never change
 * the word lists. Computers use a separate App with read and write. These values are
 * public: a device-flow App has no secret in the app.
 */
final class GitHubApp {
    /** The read-only App's client ID. */
    static final String CLIENT_ID = "Iv23lizOjuqOKKNWorlm";
    /** The App's name in its github.com/apps/&lt;slug&gt; address. */
    static final String SLUG = "voiceflow-word-lists-read-only";
    static final String DEVICE_URL = "https://github.com/login/device";
    static final String AUTHORIZATIONS_URL = "https://github.com/settings/apps/authorizations";

    private GitHubApp() {
    }

    /** Where the App is installed on a repository. */
    static String installUrl() {
        return "https://github.com/apps/" + SLUG + "/installations/new";
    }
}
