package net.officefloor.hq.app.settings;

/** The role a sheet gives a user name; a name with none is a clerk. */
public enum Role {
    /** May settle a claim only when its due is at or below the sheet's clerk limit. */
    CLERK,
    /** May settle any claim. */
    STEWARD
}
