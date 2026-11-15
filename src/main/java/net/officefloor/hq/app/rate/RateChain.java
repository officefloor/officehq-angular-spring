package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The rates in force on a sheet: a grade with no rate of its own in force on a date takes its
 *  parent sheet's for that grade and date, then its parent's parent's, up to {@link #LEVELS} levels up. */
@Component
public class RateChain {

    /** How many levels of parent sheets a rate is looked for in. */
    public static final int LEVELS = 3;

    private final RateRepository rates;
    private final SheetRepository sheets;

    public RateChain(RateRepository rates, SheetRepository sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    /** The ids of the sheet's ancestors, its parent first, up to {@link #LEVELS} levels up. */
    @Transactional(readOnly = true)
    public List<String> ancestors(String sheetId) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        String at = sheetId;
        while (out.size() < LEVELS) {
            String parent = sheets.findById(at).map(Sheet::getParent).orElse(null);
            if (parent == null || !seen.add(parent)) {
                break;
            }
            out.add(parent);
            at = parent;
        }
        return out;
    }

    /** The rates of each of the sheet's ancestors, its parent's first. */
    @Transactional(readOnly = true)
    public List<List<Rate>> inherited(String sheetId) {
        return ancestors(sheetId).stream().map(rates::findBySheetIdOrderByGradeAsc).toList();
    }

    /** The rates in force on the sheet, with what it inherits, as one list. */
    @Transactional(readOnly = true)
    public List<Rate> effective(String sheetId) {
        return effective(sheetId, rates.findBySheetIdOrderByGradeAsc(sheetId));
    }

    /** The rates in force on the sheet given its own (as they stood on a date, say), with what it
     *  inherits from its ancestors now, as one list. */
    @Transactional(readOnly = true)
    public List<Rate> effective(String sheetId, List<Rate> own) {
        return flatten(sheetId, own, inherited(sheetId));
    }

    /** The grade's rate in force on a date on the sheet: its own, else the nearest ancestor's. */
    @Transactional(readOnly = true)
    public Optional<Rate> findInForce(String sheetId, String grade, LocalDate on) {
        Optional<Rate> own = rates.findInForce(sheetId, grade, on);
        if (own.isPresent()) {
            return own;
        }
        for (String ancestor : ancestors(sheetId)) {
            Optional<Rate> found = rates.findInForce(ancestor, grade, on);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** One list of rates that has, for each grade and date, the rate in force then on the first of
     *  the levels (the sheet's own, then each ancestor's) that has one: the dates are cut where any
     *  level's rate starts or ends, and each piece takes the rate the levels give it. With nothing
     *  inherited it is the sheet's own. */
    public static List<Rate> flatten(String sheetId, List<Rate> own, List<List<Rate>> inherited) {
        if (inherited.stream().allMatch(List::isEmpty)) {
            return own;
        }
        List<List<Rate>> levels = new ArrayList<>();
        levels.add(own);
        levels.addAll(inherited);
        TreeSet<String> grades = new TreeSet<>();
        levels.forEach(l -> l.forEach(r -> grades.add(r.getGrade())));
        List<Rate> out = new ArrayList<>();
        for (String grade : grades) {
            TreeSet<LocalDate> cuts = new TreeSet<>();
            for (List<Rate> level : levels) {
                for (Rate r : level) {
                    if (r.getGrade().equals(grade)) {
                        if (r.getFrom() != null) {
                            cuts.add(r.getFrom());
                        }
                        if (r.getTo() != null) {
                            cuts.add(r.getTo().plusDays(1));
                        }
                    }
                }
            }
            List<LocalDate> starts = new ArrayList<>();
            starts.add(null);
            starts.addAll(cuts);
            for (int i = 0; i < starts.size(); i++) {
                LocalDate start = starts.get(i);
                LocalDate next = i + 1 < starts.size() ? starts.get(i + 1) : null;
                LocalDate end = next == null ? null : next.minusDays(1);
                LocalDate on = start != null ? start : end != null ? end : LocalDate.EPOCH;
                for (List<Rate> level : levels) {
                    Optional<Rate> found = inForce(level, grade, on);
                    if (found.isPresent()) {
                        out.add(new Rate(sheetId, grade, start, end, found.get().getPct()));
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static Optional<Rate> inForce(List<Rate> level, String grade, LocalDate on) {
        return level.stream()
                .filter(r -> r.getGrade().equals(grade) && r.appliesOn(on))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.runsThrough(on));
    }
}
