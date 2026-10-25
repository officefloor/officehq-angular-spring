package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;

/** The rates a sheet's figures are worked out from: a grade with no rate in force on a date on the sheet
 * uses its parent's rate for that grade and date, then its parent's parent's, up to {@link #LEVELS_UP}
 * levels up; then, for a sheet that was cloned, the rates of the sheet it was cloned from, worked out
 * alike (its own, its parents', then its source's). */
@Component
public class RateChain {

    /** How many levels of parents are looked to for a rate. */
    public static final int LEVELS_UP = 3;

    private final RateRepository rates;
    private final SheetRepository sheets;

    public RateChain(RateRepository rates, SheetRepository sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    /** The sheet's id, then its parent's, and so on up to {@link #LEVELS_UP} levels up; then, when the
     * sheet was cloned, its source's chain alike. A sheet comes at most once. */
    public List<String> chain(String sheetId) {
        Set<String> chain = new LinkedHashSet<>();
        String from = sheetId;
        while (from != null && !chain.contains(from)) {
            Sheet start = sheets.findById(from).orElse(null);
            chain.add(from);
            String id = start == null ? null : start.getParentId();
            for (int up = 0; id != null && up < LEVELS_UP && chain.add(id); up++) {
                id = sheets.findById(id).map(Sheet::getParentId).orElse(null);
            }
            from = start == null ? null : start.getSourceId();
        }
        return List.copyOf(chain);
    }

    /** The sheet's rates, then its parent's, and so on (see {@link #chain}): each by grade, then by from-date. */
    public List<List<Rate>> levels(String sheetId) {
        List<List<Rate>> levels = new ArrayList<>();
        for (String id : chain(sheetId)) levels.add(rates.findBySheetIdOrderByGradeAndFrom(id));
        return levels;
    }

    /** The grade's rate in force on {@code date} on the sheet, or else on the nearest parent that has one
     * in force then (see {@link RateRepository#findInForce}). */
    public Optional<Rate> inForce(String sheetId, String grade, LocalDate date) {
        for (String id : chain(sheetId)) {
            Optional<Rate> rate = rates.findInForce(id, grade, date);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }

    /** The grade's rate in force on {@code date} on the first of {@code levels} (a sheet's rates, then
     * its parents') that has one in force then. */
    public static Optional<Rate> inForce(List<List<Rate>> levels, String grade, LocalDate date) {
        for (List<Rate> level : levels) {
            Optional<Rate> rate = RateRepository.inForce(level, grade, date);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }

    /** The grade's rate in force on {@code date} for a claim added at {@code added}, on the sheet or else
     * on the nearest parent that has one in force for it then (see {@link RateRepository#inForce(List,
     * String, LocalDate, long)}). */
    public Optional<Rate> inForce(String sheetId, String grade, LocalDate date, long added) {
        for (String id : chain(sheetId)) {
            Optional<Rate> rate = RateRepository.inForce(rates.findBySheetIdAndGrade(id, grade), grade, date, added);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }

    /** The grade's rate in force on {@code date} for a claim added at {@code added}, on the first of
     * {@code levels} that has one in force for it then. */
    public static Optional<Rate> inForce(List<List<Rate>> levels, String grade, LocalDate date, long added) {
        for (List<Rate> level : levels) {
            Optional<Rate> rate = RateRepository.inForce(level, grade, date, added);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }
}
