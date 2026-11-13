package net.officefloor.hq.app;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Who is signed in: the screen sends the typed name with every request in the {@link #HEADER}
 * header, URI-encoded (a header carries only plain ASCII). The audit records it as the change's
 * author.
 */
public final class SignedInUser {

    public static final String HEADER = "X-User";

    private SignedInUser() {
    }

    /** The signed-in user's name from the header's value. */
    public static String decode(String header) {
        return URLDecoder.decode(header, StandardCharsets.UTF_8).trim();
    }
}
