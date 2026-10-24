package net.officefloor.hq.app.settings;

/** A user's role on a sheet. Every name is a clerk unless made a steward or a viewer. */
public enum Role {
    /** May settle a claim only when its due is at or below the sheet's clerk limit. */
    CLERK,
    /** May settle any claim. */
    STEWARD,
    /** Sees every screen and figure and may change nothing. */
    VIEWER
}
