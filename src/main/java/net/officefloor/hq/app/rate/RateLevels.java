package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A sheet's rates by grade, then those of each of its ancestors, nearest first (see
 * {@link net.officefloor.hq.app.sheet.SheetParent}): a grade's rate on a date is the sheet's own in
 * force then, else the nearest ancestor's in force then.
 */
public record RateLevels(List<Map<String, List<Rate>>> levels) {

    /** The grade's rate on the date: the first level's that has one in force then. */
    public Optional<Rate> inForce(String grade, LocalDate date) {
        for (Map<String, List<Rate>> level : levels) {
            Optional<Rate> rate = Rate.inForce(level.getOrDefault(grade, List.of()), date);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }
}
