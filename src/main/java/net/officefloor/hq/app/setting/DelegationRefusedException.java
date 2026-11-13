package net.officefloor.hq.app.setting;

/** A delegation by anyone but a steward, or with a to-date before its from-date. */
public class DelegationRefusedException extends RuntimeException {

    public DelegationRefusedException(String message) {
        super(message);
    }
}
