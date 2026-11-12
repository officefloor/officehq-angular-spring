package net.officefloor.hq.app.role;

/** A delegation that cannot be made: the user is not a steward, or the to-date is before the from-date. */
public class DelegationRefusedException extends RuntimeException {

    public DelegationRefusedException(String message) {
        super(message);
    }
}
