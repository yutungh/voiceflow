package com.voiceflowkeyboard.ime;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Who this phone is to the sync gateway: its device id (made once on the phone),
 * the user it belongs to, and the gateway's address. The token itself is kept by
 * {@link TokenVault}, encrypted, and never appears here, in a URL or in a log.
 */
final class Enrolment {
    /** "ph" plus 8 random hex characters. */
    static final Pattern DEVICE_ID = Pattern.compile("ph[0-9a-f]{8}");
    /** The pack's user names: a short lower-case name. */
    static final Pattern USER = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    /** What the gateway's token tool issues: "vfs1." and 43 base64url characters. */
    static final Pattern TOKEN = Pattern.compile("vfs1\\.[A-Za-z0-9_-]{43}");

    final String device;
    final String user;
    final String gateway;

    Enrolment(String device, String user, String gateway) {
        this.device = device;
        this.user = user;
        this.gateway = gateway;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    static String newDeviceId() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder("ph");
        for (byte b : bytes) {
            id.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return id.toString();
    }

    static boolean validDevice(String device) {
        return device != null && DEVICE_ID.matcher(device).matches();
    }

    static boolean validUser(String user) {
        return user != null && USER.matcher(user).matches();
    }

    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /**
     * The gateway's origin, "https://host" or "https://host:port", or null when
     * the address is not a bare HTTPS origin. Plain HTTP, a user name or password,
     * a path, a query or a fragment are all refused: every route is built from
     * this origin and fixed paths only.
     */
    static String normalizeGateway(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || host.isEmpty()
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return null;
        }
        if (!host.matches("[A-Za-z0-9.-]{1,253}")) {
            return null;
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            return null;
        }
        return "https://" + host.toLowerCase(Locale.ROOT) + (port > 0 && port != 443 ? ":" + port : "");
    }
}
