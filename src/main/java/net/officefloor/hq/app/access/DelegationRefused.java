package net.officefloor.hq.app.access;

/** A delegation that cannot be made: the one delegating is no steward, or its to-date is before its from-date. */
public class DelegationRefused extends RuntimeException {

    public DelegationRefused(String message) {
        super(message);
    }
}
