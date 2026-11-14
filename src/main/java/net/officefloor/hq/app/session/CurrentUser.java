package net.officefloor.hq.app.session;

/** The signed-in name travels with every request in this header (there is no password). */
public final class CurrentUser {

    public static final String HEADER = "X-User";

    private CurrentUser() {
    }
}
