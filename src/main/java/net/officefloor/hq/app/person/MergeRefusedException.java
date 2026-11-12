package net.officefloor.hq.app.person;

/** A merge of one person into another that is not allowed: by anyone but a steward, of a person into
 *  themselves, or when either is retired. */
public class MergeRefusedException extends RuntimeException {

    public MergeRefusedException(String message) {
        super(message);
    }
}
