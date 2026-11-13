package net.officefloor.hq.app.statement;

/** Only a steward cancels a statement, and only a person with one has a statement to cancel. */
public class StatementCancelRefusedException extends RuntimeException {

    public StatementCancelRefusedException(String message) {
        super(message);
    }
}
