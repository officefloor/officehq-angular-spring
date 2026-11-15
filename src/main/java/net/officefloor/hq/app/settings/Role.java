package net.officefloor.hq.app.settings;

/** A user's role on a sheet. Any name without one is a clerk. A viewer sees the sheet and changes
 *  nothing on it. */
public enum Role {
    CLERK, STEWARD, VIEWER;

    /** The role as it is stored, shown and audited. */
    public String key() {
        return name().toLowerCase();
    }

    public static Role of(String key) {
        return valueOf(key.toUpperCase());
    }
}
