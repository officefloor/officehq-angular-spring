package net.officefloor.hq.app.setting;

/** A user's role on a sheet: CLERK for any name not given one. A clerk's settling is limited. */
public enum Role {
    CLERK, STEWARD;

    /** The role as the screen and the audit file show it. */
    public String label() { return name().toLowerCase(); }
}
