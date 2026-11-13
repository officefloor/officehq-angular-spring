package net.officefloor.hq.app.claim;

/** Only a steward moves a claim to another person. */
public class ReassignRefusedException extends RuntimeException {

    public ReassignRefusedException(String user) {
        super(user + " is not a steward: only a steward moves a claim to another person");
    }
}
