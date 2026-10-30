package net.officefloor.hq.app.claim;

import java.time.LocalDate;

/** Whether a person is on hold on a date. */
@FunctionalInterface
public interface Holds {

    /** No hold on any date. */
    Holds NONE = date -> false;

    /** Whether a hold covers the date. */
    boolean on(LocalDate date);
}
