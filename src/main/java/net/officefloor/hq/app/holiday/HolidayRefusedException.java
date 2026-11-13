package net.officefloor.hq.app.holiday;

/** A holiday that cannot be added: its date is already on the sheet's list. */
public class HolidayRefusedException extends RuntimeException {

    public HolidayRefusedException(String message) {
        super(message);
    }
}
