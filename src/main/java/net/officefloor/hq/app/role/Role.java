package net.officefloor.hq.app.role;

/** A user's role on a sheet. Any user name without one is a clerk; a viewer may change nothing;
 * a restricted user sees on the people list only the people of their crews and themselves. */
public enum Role {
    CLERK, STEWARD, VIEWER, RESTRICTED;

    /** The role as stored and shown: lower case. */
    public String value() {
        return name().toLowerCase();
    }

    public static Role of(String value) {
        return valueOf(value.toUpperCase());
    }
}
