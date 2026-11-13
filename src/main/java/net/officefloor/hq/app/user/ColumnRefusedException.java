package net.officefloor.hq.app.user;

/** A column that cannot be switched: it is not one the people list can hide. */
public class ColumnRefusedException extends RuntimeException {

    public ColumnRefusedException(String message) {
        super(message);
    }
}
