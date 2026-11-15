package net.officefloor.hq.app.claim;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Counting business days: every day but a Saturday, a Sunday or one of the sheet's holidays. */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** Whether {@code day} is a business day. */
    public static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    /** The {@code count}-th business day after {@code from}, counting forward from the day after
     *  it; {@code from} itself when {@code count} is 0. */
    public static LocalDate after(LocalDate from, int count, Set<LocalDate> holidays) {
        LocalDate day = from;
        for (int left = count; left > 0; ) {
            day = day.plusDays(1);
            if (isBusinessDay(day, holidays)) {
                left--;
            }
        }
        return day;
    }
}
