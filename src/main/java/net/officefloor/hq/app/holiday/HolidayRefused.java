package net.officefloor.hq.app.holiday;

/** A holiday was refused: nothing was written. */
public class HolidayRefused extends RuntimeException {

    public HolidayRefused(String message) {
        super(message);
    }
}
