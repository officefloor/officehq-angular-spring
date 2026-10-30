package net.officefloor.hq.app.crew;

/** A crew, or a member of one, was refused: nothing was written. */
public class CrewRefused extends RuntimeException {

    public CrewRefused(String message) {
        super(message);
    }
}
