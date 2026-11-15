package net.officefloor.hq.app.settings;

/** A user's role on a sheet. Any name without one is a clerk. A viewer sees the sheet and changes
 *  nothing on it. A restricted user acts as a clerk, but sees on the people list only the people of
 *  the crews its person leads or belongs to, and that person. */
public enum Role {
    CLERK, STEWARD, VIEWER, RESTRICTED;

    /** The role as it is stored, shown and audited. */
    public String key() {
        return name().toLowerCase();
    }

    public static Role of(String key) {
        return valueOf(key.toUpperCase());
    }
}
