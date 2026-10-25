package net.officefloor.hq.app.settings;

/** A user's role on a sheet. Every name is a clerk unless made a steward, a viewer or restricted. */
public enum Role {
    /** May settle a claim only when its due is at or below the sheet's clerk limit. */
    CLERK,
    /** May settle any claim. */
    STEWARD,
    /** Sees every screen and figure and may change nothing. */
    VIEWER,
    /** Acts as a clerk, but its people list shows only the people of the crews it leads or belongs to (by
     * a person with its name) and that person themselves; every other figure is still over everyone. */
    RESTRICTED
}
