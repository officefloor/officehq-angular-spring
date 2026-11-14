package net.officefloor.hq.app.crew;

/** A crew or a crew member that cannot be added: a blank or used name, a lead or member who is not a
 *  person of the sheet, or a member who leads the crew or is in it already. */
public class CrewRefusedException extends RuntimeException {

    public CrewRefusedException(String message) {
        super(message);
    }
}
