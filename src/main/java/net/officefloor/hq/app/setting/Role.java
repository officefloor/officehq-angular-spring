package net.officefloor.hq.app.setting;

/**
 * A user's role on a sheet: CLERK for any name not given one. A clerk's settling is limited. A
 * VIEWER sees everything and can change nothing.
 */
public enum Role {
    CLERK, STEWARD, VIEWER;

    /** The role as the screen and the audit file show it. */
    public String label() { return name().toLowerCase(); }
}
