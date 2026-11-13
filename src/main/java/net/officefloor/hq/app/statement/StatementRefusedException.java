package net.officefloor.hq.app.statement;

/** A statement is taken as of a day on or before the sheet's today, for a person on the sheet then. */
public class StatementRefusedException extends RuntimeException {

    public StatementRefusedException(String message) {
        super(message);
    }
}
