package com.voiceflowkeyboard.ime;

import java.io.IOException;

/**
 * Where the phone reads the shared word lists from: the vf-sync gateway
 * ({@link GatewayClient}) or a private GitHub repository ({@link GitHubClient}).
 *
 * <p>Both answer the same three questions, so {@link WordLists#refresh} keeps one
 * set of rules (the offline copy, the last good copy of each layer, edits kept
 * by name). The index is a {@code vf-sync-manifest}: {@code layers} name each
 * file's revision, {@code ops} the edit files. A refusal is a
 * {@link GatewayClient.GatewayException}, whichever source refused.
 */
interface WordSource {
    /** The index, or "not modified" (an empty 304) for {@code etag}. */
    GatewayClient.Fetched manifest(String etag) throws IOException;

    /** One layer file: "household", "generated" or "personal". */
    GatewayClient.Fetched layer(String layer) throws IOException;

    /** One edit file, by a name the index listed. */
    GatewayClient.Fetched op(String name) throws IOException;

    /**
     * The revision of a file's bytes, in the form the index uses for it
     * ({@code sha256:<hex>} for the gateway, {@code git:<blob id>} for GitHub).
     */
    String revisionOf(byte[] bytes);

    /**
     * The revision the index gives a layer entry ({@code {"sha256": …}} from the
     * gateway, {@code {"revision": …}} from GitHub), or "" when it gives none.
     */
    static String revision(org.json.JSONObject entry) {
        if (entry == null) {
            return "";
        }
        String revision = entry.optString("revision", "");
        if (!revision.isEmpty()) {
            return revision;
        }
        String sha = entry.optString("sha256", "");
        return sha.isEmpty() ? "" : "sha256:" + sha;
    }
}
