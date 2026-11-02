package net.officefloor.hq.app.holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Counting business days: every day but a Saturday, a Sunday or a holiday of the sheet. */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** Whether the day is a business day: not a Saturday, a Sunday or one of the holidays. */
    public static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /** The terms-th business day after the day, counting from the day after it; the day itself when
     *  the terms are none. */
    public static LocalDate after(LocalDate day, int terms, Set<LocalDate> holidays) {
        LocalDate d = day;
        for (int counted = 0; counted < terms;) {
            d = d.plusDays(1);
            if (isBusinessDay(d, holidays)) {
                counted++;
            }
        }
        return d;
    }
}
