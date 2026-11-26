package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Provisioning over USB, from {@code adb shell am broadcast}. The manifest
 * protects this receiver with {@code android.permission.DUMP}, which the adb
 * shell holds and ordinary apps cannot, so only a person with the unlocked phone
 * on a trusted USB connection can enrol it or send it a reset.
 *
 * <ul>
 *   <li>{@value #ACTION_INFO}: prints the device id and enrolment state.</li>
 *   <li>{@value #ACTION_ENROL}: extras {@code user}, {@code gateway}, {@code token}.</li>
 *   <li>{@value #ACTION_RESET_STYLES}: extras {@code id}, {@code user}, {@code device};
 *       runs only if they match this phone's enrolment ({@link StyleReset}).</li>
 *   <li>{@value #ACTION_SYNC_NOW}: asks for a sync run as soon as there is a network.</li>
 *   <li>{@value #ACTION_STYLE_DIGEST}: SHA-256 of each stored style value, never the text.</li>
 *   <li>{@value #ACTION_SET_STYLE_SPEECH} (0.9.3): extras {@code style} (a style id, a word-list
 *       style's included: {@code list_<id>}), {@code provider} ({@code soniox}, {@code openai}, ...,
 *       {@code app} for the app default, or {@code same} to go back to "Same as app") and optionally
 *       {@code model}. Replies with the stored choice, what a dictation would use now, and whether
 *       that provider's key is saved (never the key).</li>
 *   <li>{@value #ACTION_SET_KEYBOARD_STYLE} (0.9.4): extras {@code keyboard} ({@code chinese}) and
 *       {@code style} (a style id, a word-list style's included, or {@code same} for "Same as
 *       English"). Replies with what is stored, what a dictation on that keyboard starts on now,
 *       and whether the style is known here.</li>
 *   <li>{@value #ACTION_SET_STYLE_WORDS} (0.9.4): extras {@code style} and {@code words}
 *       ({@code on} or {@code off}): whether that style's dictations use the word lists' words.
 *       Replies with the stored value.</li>
 * </ul>
 *
 * The reply is the broadcast's result data. It never contains the token.
 */
public final class SyncCommandReceiver extends BroadcastReceiver {
    static final String ACTION_INFO = "com.voiceflowkeyboard.ime.action.SYNC_INFO";
    static final String ACTION_ENROL = "com.voiceflowkeyboard.ime.action.ENROL";
    static final String ACTION_RESET_STYLES = "com.voiceflowkeyboard.ime.action.RESET_STYLES";
    static final String ACTION_SYNC_NOW = "com.voiceflowkeyboard.ime.action.SYNC_NOW";
    /** Hashes of every stored style setting, to compare with an archive without reading any text. */
    static final String ACTION_STYLE_DIGEST = "com.voiceflowkeyboard.ime.action.STYLE_DIGEST";
    /** 0.9.3: a style's own speech model, set over USB ({@link SpeechModels}). */
    static final String ACTION_SET_STYLE_SPEECH = "com.voiceflowkeyboard.ime.action.SET_STYLE_SPEECH";
    /** 0.9.4: the Chinese keyboard's style, set over USB ({@link KeyboardStyles}). */
    static final String ACTION_SET_KEYBOARD_STYLE = "com.voiceflowkeyboard.ime.action.SET_KEYBOARD_STYLE";
    /** 0.9.4: a style's word list on or off, set over USB ({@link Prefs#styleWordsOn}). */
    static final String ACTION_SET_STYLE_WORDS = "com.voiceflowkeyboard.ime.action.SET_STYLE_WORDS";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String reply;
        int code = 1;
        try {
            if (ACTION_INFO.equals(action)) {
                reply = info(context);
            } else if (ACTION_ENROL.equals(action)) {
                Enrolment enrolment = SyncStore.enrol(
                        context,
                        intent.getStringExtra("user"),
                        intent.getStringExtra("gateway"),
                        intent.getStringExtra("token")
                );
                SyncScheduler.kick(context);
                reply = "enrolled device=" + enrolment.device + " user=" + enrolment.user + " gateway=" + enrolment.gateway;
            } else if (ACTION_RESET_STYLES.equals(action)) {
                String decision = StyleReset.run(context, new StyleReset.Instruction(
                        intent.getStringExtra("id"),
                        intent.getStringExtra("user"),
                        intent.getStringExtra("device")
                ));
                reply = StyleReset.APPLY.equals(decision)
                        ? "reset done: styles now start from Clean (Natural)"
                        : "reset refused: " + decision;
                if (!StyleReset.APPLY.equals(decision)) {
                    code = 2;
                }
            } else if (ACTION_SET_STYLE_SPEECH.equals(action)) {
                reply = setStyleSpeech(context, intent.getStringExtra("style"), intent.getStringExtra("provider"),
                        intent.getStringExtra("model"));
            } else if (ACTION_SET_KEYBOARD_STYLE.equals(action)) {
                reply = setKeyboardStyle(context, intent.getStringExtra("keyboard"), intent.getStringExtra("style"));
            } else if (ACTION_SET_STYLE_WORDS.equals(action)) {
                reply = setStyleWords(context, intent.getStringExtra("style"), intent.getStringExtra("words"));
            } else if (ACTION_STYLE_DIGEST.equals(action)) {
                reply = Prefs.styleDigest(context);
            } else if (ACTION_SYNC_NOW.equals(action)) {
                SyncScheduler.kick(context);
                reply = SyncStore.enrolment(context) == null ? "not enrolled" : "sync scheduled";
            } else {
                reply = "unknown action";
                code = 2;
            }
        } catch (IllegalArgumentException e) {
            // Validation messages name the field, never its value.
            reply = "refused: " + e.getMessage();
            code = 2;
        } catch (Exception e) {
            reply = "failed: " + e.getClass().getSimpleName();
            code = 3;
        }
        if (code == 1) {
            code = 0;
        }
        setResultCode(code);
        setResultData(reply);
    }

    /** Validates, stores and reads back one style's speech model. */
    static String setStyleSpeech(Context context, String style, String provider, String model) {
        String id = style == null ? "" : style.trim();
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,119}")) {
            throw new IllegalArgumentException("style must be a style id");
        }
        String p = provider == null ? "" : provider.trim();
        String m = model == null ? "" : model.trim();
        if (m.length() > 100 || !m.matches("[A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException("model must be a model id");
        }
        if ("same".equals(p)) {
            Prefs.setSpeechModelForStyle(context, id, null);
        } else if (SpeechModels.APP.equals(p)) {
            Prefs.setSpeechModelForStyle(context, id, SpeechModels.APP);
        } else if (Prefs.supportsTranscription(p)) {
            Prefs.setSpeechModelForStyle(context, id, SpeechModels.encode(new SpeechModels.Choice(p, m)));
        } else {
            throw new IllegalArgumentException("provider must be same, app, or a speech provider");
        }
        SpeechModels.Stored stored = SpeechModels.parse(Prefs.speechModelForStyle(context, id));
        SpeechModels.Resolved resolved = SpeechModels.atRecordStart(context, id);
        String choice = stored.kind == SpeechModels.Kind.OWN ? stored.own.provider + "/" + stored.own.effectiveModel()
                : stored.kind == SpeechModels.Kind.APP_DEFAULT ? "app" : "same";
        String keyProvider = stored.kind == SpeechModels.Kind.OWN ? stored.own.provider : resolved.used.provider;
        boolean key = Prefs.hasApiKeyForProvider(context, keyProvider);
        return "style=" + id
                + " choice=" + choice
                + " resolved=" + resolved.used.provider + "/" + resolved.used.effectiveModel()
                + (resolved.unavailable != null ? " (" + resolved.unavailable.provider + " unavailable)" : "")
                + " key=" + (key ? "yes" : "no")
                + " styleKnown=" + (Prefs.isListStyle(id) ? (ListStyles.find(id) != null ? "yes" : "not-yet")
                : (containsStyle(context, id) ? "yes" : "no"));
    }

    private static String styleId(String style) {
        String id = style == null ? "" : style.trim();
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,119}")) {
            throw new IllegalArgumentException("style must be a style id");
        }
        return id;
    }

    /** yes, no, or (a word-list style before the lists were read since the app started) not-yet. */
    static String styleKnown(Context context, String id) {
        if (containsStyle(context, id)) {
            return "yes";
        }
        return Prefs.isListStyle(id) && !ListStyles.loaded() ? "not-yet" : "no";
    }

    /** 0.9.4: validates, stores and reads back the Chinese keyboard's style. */
    static String setKeyboardStyle(Context context, String keyboard, String style) {
        String k = keyboard == null ? "" : keyboard.trim();
        if (!"chinese".equals(k)) {
            throw new IllegalArgumentException("keyboard must be chinese");
        }
        String s = style == null ? "" : style.trim();
        String id = "same".equals(s) ? "" : styleId(s);
        // Kept even when the style isn't here (yet): a word-list style may arrive with the next sync.
        Prefs.chooseChineseKeyboardStyle(context, id);
        String stored = Prefs.chineseKeyboardStyle(context);
        return "keyboard=chinese style=" + (stored.isEmpty() ? "same" : stored)
                + (stored.isEmpty() ? "" : " styleKnown=" + styleKnown(context, stored))
                + " startsOn=" + Prefs.keyboardStyle(context, true)
                + " english=" + Prefs.activePreset(context)
                + " chineseInput=" + (Prefs.chineseInputEnabled(context) ? "on" : "off")
                + " styleChoice=" + Prefs.styleChoiceMode(context);
    }

    /** 0.9.4: validates, stores and reads back whether a style uses the word lists' words. */
    static String setStyleWords(Context context, String style, String words) {
        String id = styleId(style);
        String w = words == null ? "" : words.trim();
        if (!"on".equals(w) && !"off".equals(w)) {
            throw new IllegalArgumentException("words must be on or off");
        }
        Prefs.setStyleWordsOn(context, id, "on".equals(w));
        return "style=" + id + " words=" + (Prefs.styleWordsOn(context, id) ? "on" : "off")
                + " styleKnown=" + styleKnown(context, id);
    }

    private static boolean containsStyle(Context context, String id) {
        for (PromptProfile profile : Prefs.promptProfiles(context)) {
            if (profile.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    static String info(Context context) {
        Enrolment enrolment = SyncStore.enrolment(context);
        StringBuilder out = new StringBuilder()
                .append("device=").append(SyncStore.deviceId(context))
                .append(" version=").append(SelfUpdater.installedVersionName(context))
                .append(" versionCode=").append(SelfUpdater.installedVersionCode(context));
        if (enrolment == null) {
            out.append(" enrolled=no");
        } else {
            out.append(" enrolled=yes user=").append(enrolment.user).append(" gateway=").append(enrolment.gateway);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            out.append(" installPermission=").append(context.getPackageManager().canRequestPackageInstalls() ? "yes" : "no");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean notify = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            out.append(" notifications=").append(notify ? "yes" : "no");
        }
        UpdateEvidence evidence = SyncStore.evidence(
                context, SelfUpdater.installedVersionCode(context), System.currentTimeMillis());
        out.append(" dictations=").append(evidence.dictations)
                .append(" errors=").append(evidence.errorCount())
                .append(" pending=").append(evidence.pendingVersionCode)
                .append(evidence.pendingState.isEmpty() ? "" : "/" + evidence.pendingState)
                .append(" lastError=").append(evidence.lastError.isEmpty() ? "none" : evidence.lastError)
                .append(" resets=").append(SyncStore.resetsDone(context).size());
        return out.toString();
    }
}
