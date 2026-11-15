package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** The sheet's own rates and those it inherits, to apply to its claims. */
    @Transactional(readOnly = true)
    public Levels levels(String sheetId) {
        return new Levels(sheetId, rates.findBySheetIdOrderByGradeAsc(sheetId), inherited(sheetId));
    }

    /** A sheet's own rates and those of each of its ancestors (its parent's first): {@link #inForce}
     *  the rates in force on it as one list, as the rates panel shows them, and {@link #forClaim} the
     *  ones a claim applies, leaving out the forward-only versions saved after it was added. */
    public static final class Levels {

        private final String sheetId;
        private final List<Rate> own;
        private final List<List<Rate>> inherited;
        private final List<Rate> inForce;
        /** Every forward-only mark the rates have, lowest first. */
        private final List<Long> marks;
        /** The rates claims apply, by how many of the marks they were added after. */
        private final Map<Integer, List<Rate>> byMark = new HashMap<>();

        public Levels(String sheetId, List<Rate> own, List<List<Rate>> inherited) {
            this.sheetId = sheetId;
            this.own = own;
            this.inherited = inherited;
            this.inForce = flatten(sheetId, own, inherited);
            TreeSet<Long> all = new TreeSet<>();
            List<List<Rate>> levels = new ArrayList<>(inherited);
            levels.add(own);
            levels.forEach(l -> l.forEach(r -> {
                if (r.getAfterClaim() != null) {
                    all.add(r.getAfterClaim());
                }
                r.getEarlier().forEach(e -> {
                    if (e.afterClaim() != null) {
                        all.add(e.afterClaim());
                    }
                });
            }));
            this.marks = List.copyOf(all);
        }

        /** The rates in force on the sheet, with what it inherits, as one list. */
        public List<Rate> inForce() {
            return inForce;
        }

        /** The rates, with what the sheet inherits, as one list, as a claim added as {@code added}
         *  (null: one added now) applies them. */
        public List<Rate> forClaim(Long added) {
            if (marks.isEmpty() || added == null) {
                return inForce;
            }
            // how many marks it was added after (one added as a mark itself was there when it was saved)
            int at = Collections.binarySearch(marks, added);
            int passed = at >= 0 ? at : -at - 1;
            if (passed == marks.size()) {
                return inForce;
            }
            return byMark.computeIfAbsent(passed, k -> flatten(sheetId, Rate.forClaim(own, added),
                    inherited.stream().map(l -> Rate.forClaim(l, added)).toList()));
        }
    }

    /** The grade's rate on a date on the sheet, as a claim added as {@code added} (null: one added
     *  now) applies it: its own, else the nearest ancestor's. */
    @Transactional(readOnly = true)
    public Optional<Rate> findInForce(String sheetId, String grade, LocalDate on, Long added) {
        Optional<Rate> own = inForce(Rate.forClaim(rates.findBySheetIdAndGrade(sheetId, grade), added), grade, on);
        if (own.isPresent()) {
            return own;
        }
        for (String ancestor : ancestors(sheetId)) {
            Optional<Rate> found = inForce(Rate.forClaim(rates.findBySheetIdAndGrade(ancestor, grade), added), grade, on);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
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
