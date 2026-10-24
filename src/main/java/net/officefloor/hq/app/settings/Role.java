package net.officefloor.hq.app.settings;

/** A user's role on a sheet. Every name is a clerk unless made a steward. */
public enum Role {
    /** May settle a claim only when its due is at or below the sheet's clerk limit. */
    CLERK,
    /** May settle any claim. */
    STEWARD
}
