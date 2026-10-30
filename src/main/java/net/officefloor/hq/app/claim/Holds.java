package net.officefloor.hq.app.claim;

import java.time.LocalDate;

/** Whether a person is on hold on a date, and whether they are retired on a sheet's today. */
@FunctionalInterface
public interface Holds {

    /** No hold on any date. */
    Holds NONE = date -> false;

    /** Whether a hold covers the date. */
    boolean on(LocalDate date);

    /** Whether the person's retirement date is reached on the sheet's today; never, unless said otherwise. */
    default boolean retiredOn(LocalDate today) {
        return false;
    }
}
