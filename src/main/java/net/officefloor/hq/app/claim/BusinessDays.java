package net.officefloor.hq.app.claim;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Business days: every day that is not a Saturday, a Sunday or a holiday on the sheet. */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** Whether a day is a business day, given the sheet's holidays. */
    public static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek weekday = day.getDayOfWeek();
        return weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /**
     * The date a claim is due by: the terms-th business day after a date, counting forward from the day
     * after it; the date itself for terms of 0.
     */
    public static LocalDate dueBy(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate day = date;
        for (int counted = 0; counted < terms; ) {
            day = day.plusDays(1);
            if (isBusinessDay(day, holidays)) {
                counted++;
            }
        }
        return day;
    }
}
