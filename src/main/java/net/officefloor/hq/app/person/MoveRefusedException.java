package net.officefloor.hq.app.person;

/** A move that may not be made: the top person cannot move up nor the bottom one down. */
public class MoveRefusedException extends RuntimeException {

    public MoveRefusedException(String message) {
        super(message);
    }
}
