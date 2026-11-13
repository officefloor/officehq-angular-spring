package net.officefloor.hq.app.claim;

/** Only a role the sheet's permission matrix allows (by the standing rule a steward) moves a claim to another person. */
public class ReassignRefusedException extends RuntimeException {

    public ReassignRefusedException(String user) {
        super(user + "'s role may not move a claim to another person");
    }
}
