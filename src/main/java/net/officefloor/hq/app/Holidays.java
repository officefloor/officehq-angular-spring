package net.officefloor.hq.app;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The workspace's holiday calendar: the {@code holidays} setting marks that it keeps one (possibly
 * empty), the dates being the rows of the {@code holiday} table. With a calendar, days in payment
 * terms are business days, Monday to Friday and not a holiday; a holiday on a Saturday is observed on
 * the Friday before it and one on a Sunday on the Monday after it. Without one they are calendar days
 * (invoices read the calendar themselves, see Invoice).
 */
@Service
public class Holidays {

    /** The setting marking that the workspace keeps a holiday calendar. */
    public static final String HOLIDAYS = "holidays";

    private final JdbcTemplate jdbc;

    public Holidays(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The holidays, earliest first; empty when the workspace keeps no calendar. */
    public Optional<Set<LocalDate>> calendar() {
        if (jdbc.queryForList("SELECT name FROM app_setting WHERE name = ?", String.class, HOLIDAYS).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TreeSet<>(jdbc.queryForList("SELECT holiday_on FROM holiday ORDER BY holiday_on", LocalDate.class)));
    }

    /** Keeps a holiday calendar of the given dates. */
    public void set(List<LocalDate> days) {
        jdbc.update("DELETE FROM holiday");
        jdbc.update("DELETE FROM app_setting WHERE name = ?", HOLIDAYS);
        jdbc.update("INSERT INTO app_setting (name, setting_value) VALUES (?, ?)", HOLIDAYS, "true");
        new TreeSet<>(days).forEach(day -> jdbc.update("INSERT INTO holiday (holiday_on) VALUES (?)", day));
    }

    /** The day the given days after from: business days with the given calendar, calendar days with none (null). */
    public LocalDate after(LocalDate from, int days) {
        return after(from, days, calendar().orElse(null));
    }

    /**
     * The day the given days after from, counting from the day after: business days, Monday to Friday and
     * not a holiday, when given a calendar; calendar days when it is null.
     */
    public static LocalDate after(LocalDate from, int days, Set<LocalDate> holidays) {
        if (holidays == null) {
            return from.plusDays(days);
        }
        Set<LocalDate> observed = new HashSet<>();
        holidays.forEach(holiday -> observed.add(observed(holiday)));
        LocalDate day = from;
        for (int left = days; left > 0;) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY && !observed.contains(day)) {
                left--;
            }
        }
        return day;
    }

    /** The weekday a holiday is observed on: the Friday before a Saturday, the Monday after a Sunday. */
    public static LocalDate observed(LocalDate holiday) {
        return switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
    }
}
