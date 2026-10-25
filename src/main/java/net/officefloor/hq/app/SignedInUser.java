package net.officefloor.hq.app;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Who is signed in: the screen sends the typed name, URI-encoded, in a request header. */
public final class SignedInUser {

    /** The request header carrying the signed-in user's name. */
    public static final String HEADER = "X-User";

    private SignedInUser() {
    }

    /** The signed-in user's name from the header's value. */
    public static String name(String header) {
        return URLDecoder.decode(header.replace("+", "%2B"), StandardCharsets.UTF_8).trim();
    }
}
