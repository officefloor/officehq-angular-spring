package net.officefloor.hq.app;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The signed-in user, as the screen sends it on every change in the {@link #HEADER} header,
 * URI-encoded so any typed name fits in a header.
 */
public final class SignedIn {

    public static final String HEADER = "X-User";

    private SignedIn() {
    }

    /** The user's name from the header's value. */
    public static String name(String header) {
        return URLDecoder.decode(header.replace("+", "%2B"), StandardCharsets.UTF_8).trim();
    }
}
