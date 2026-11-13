package net.officefloor.hq.app.claim;

/** Only a steward approves or rejects a PENDING claim. */
public class ApprovalRefusedException extends RuntimeException {

    public ApprovalRefusedException(String user) {
        super(user + " is not a steward: only a steward approves or rejects a pending claim");
    }
}
