package net.officefloor.hq.app.rate;

import java.time.LocalDate;

/** Which grade is in force on a date. */
@FunctionalInterface
public interface Grades {

    /** The grade in force on the date. */
    String on(LocalDate date);
}
