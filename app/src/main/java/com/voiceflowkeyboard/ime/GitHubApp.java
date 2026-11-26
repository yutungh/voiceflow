package com.voiceflowkeyboard.ime;

/**
 * The GitHub App phones connect to (docs/GITHUB-SYNC.md): "VoiceFlow Word Lists", the
 * same App computers use, so a user installs one App on the word-list repository. It
 * asks for Contents: read and write; the phone only reads today. These values are
 * public: a device-flow App has no secret in the app.
 */
final class GitHubApp {
    /** The VoiceFlow Word Lists App's client ID (the same as VoiceFlow Desktop's). */
    static final String CLIENT_ID = "Iv23liVu0ypF3zlpDwKj";
    /** The App's name in its github.com/apps/&lt;slug&gt; address. */
    static final String SLUG = "voiceflow-word-lists";
    static final String DEVICE_URL = "https://github.com/login/device";
    static final String AUTHORIZATIONS_URL = "https://github.com/settings/apps/authorizations";

    private GitHubApp() {
    }

    /** Where the App is installed on a repository. */
    static String installUrl() {
        return "https://github.com/apps/" + SLUG + "/installations/new";
    }
}
