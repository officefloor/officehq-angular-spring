package net.officefloor.hq.app.crew;

/** A crew that cannot be added (a blank name, one already taken, or no lead of the sheet), or a member that
 *  cannot be added to one (its lead, or a person already in it). */
public class CrewRefusedException extends RuntimeException {

    public CrewRefusedException(String message) {
        super(message);
    }
}
