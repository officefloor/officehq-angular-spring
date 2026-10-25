package net.officefloor.hq.app.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Business days: every day that is not a Saturday, a Sunday or one of the sheet's holidays. */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** Whether the day is a business day, given the sheet's holidays. */
    public static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /** The terms-th business day after the date, counting forward from the day after it; the date
     *  itself for terms of 0. */
    public static LocalDate after(LocalDate date, int terms, Set<LocalDate> holidays) {
        LocalDate day = date;
        for (int counted = 0; counted < terms;) {
            day = day.plusDays(1);
            if (isBusinessDay(day, holidays)) counted++;
        }
        return day;
    }
}
