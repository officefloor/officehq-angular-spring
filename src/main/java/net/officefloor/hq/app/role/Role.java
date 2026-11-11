package net.officefloor.hq.app.role;

/** A user's role on a sheet. Any user name without one is a clerk. */
public enum Role {
    CLERK, STEWARD;

    /** The role as stored and shown: lower case. */
    public String value() {
        return name().toLowerCase();
    }

    public static Role of(String value) {
        return valueOf(value.toUpperCase());
    }
}
