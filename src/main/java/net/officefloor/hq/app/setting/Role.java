package net.officefloor.hq.app.setting;

/**
 * A user's role on a sheet: CLERK for any name not given one. A clerk's settling is limited. A
 * VIEWER sees everything and can change nothing. A RESTRICTED user acts as a clerk, but their people
 * list shows only the people of the crews they lead or belong to and themselves.
 */
public enum Role {
    CLERK, STEWARD, VIEWER, RESTRICTED;

    /** The role as the screen and the audit file show it. */
    public String label() { return name().toLowerCase(); }
}
