package net.officefloor.hq.app.claim;

/** A claim cannot move to the person asked for. */
public class MoveRefusedException extends RuntimeException {

    public MoveRefusedException(String message) {
        super(message);
    }
}
