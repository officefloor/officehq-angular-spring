package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The rates a sheet's dues are read at: its own, then its parent's, its parent's parent's and that one's parent's.
 *  A grade with no rate in force on a date on the sheet takes the first rate in force for it on that date up the
 *  line. */
public record RateChain(List<List<Rate>> levels) {

    /** How many levels above a sheet its rates are looked for. */
    public static final int ANCESTORS = 3;

    /** The rate in force for a grade on a date: the sheet's own, else the nearest ancestor's; none when no sheet in
     *  the line has one. */
    public Optional<Rate> inForceOn(String grade, LocalDate date) {
        for (List<Rate> level : levels) {
            Optional<Rate> rate = Rate.inForceOn(level.stream().filter(r -> r.getGrade().equals(grade)).toList(), date);
            if (rate.isPresent()) {
                return rate;
            }
        }
        return Optional.empty();
    }

    /** The same line with the sheet's own rates in place of those it has. */
    public RateChain withOwn(List<Rate> own) {
        return new RateChain(Stream.concat(Stream.of(own), levels.stream().skip(1)).toList());
    }
}
