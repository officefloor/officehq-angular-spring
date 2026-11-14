package net.officefloor.hq.app.person;

/** A merge that may not be made: by anyone but a steward, of a person into themselves, or with either
 *  person retired. */
public class MergeRefusedException extends RuntimeException {

    public MergeRefusedException(String message) {
        super(message);
    }
}
