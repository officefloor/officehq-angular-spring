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
 * levels up. */
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

    /** The sheet's id, then its parent's, and so on up to {@link #LEVELS_UP} levels up. */
    public List<String> chain(String sheetId) {
        Set<String> chain = new LinkedHashSet<>();
        String id = sheetId;
        while (id != null && chain.size() <= LEVELS_UP && chain.add(id)) {
            id = sheets.findById(id).map(Sheet::getParentId).orElse(null);
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
}
