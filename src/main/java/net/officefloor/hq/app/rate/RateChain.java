package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;

/**
 * The rates that apply on a sheet: a grade with no rate in force on a date on the sheet takes its parent sheet's rate
 * for that date, then the parent's parent's, up to {@link #LEVELS_UP} levels up.
 */
@Component
public class RateChain {

    /** How many levels of parent sheets a rate is looked for in. */
    public static final int LEVELS_UP = 3;

    private final RateRepository rates;
    private final RateChangeRepository changes;
    private final SheetRepository sheets;

    public RateChain(RateRepository rates, RateChangeRepository changes, SheetRepository sheets) {
        this.rates = rates;
        this.changes = changes;
        this.sheets = sheets;
    }

    /** The sheet's id, then its parent's, and so on, up to {@link #LEVELS_UP} levels up. */
    public List<String> lineage(String sheetId) {
        List<String> ids = new ArrayList<>(List.of(sheetId));
        String at = sheetId;
        while (ids.size() <= LEVELS_UP) {
            String parent = sheets.findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || ids.contains(parent)) {
                break;
            }
            ids.add(parent);
            at = parent;
        }
        return ids;
    }

    /** Every grade's rates as they apply on the sheet, by grade and then from-date (see {@link Rate#chained}). */
    public List<Rate> applying(String sheetId) {
        return applying(sheetId, rates::findBySheetIdOrderByGradeAscFromAsc);
    }

    /**
     * Every grade's rates as they applied on the sheet at the end of the day: each sheet in its lineage with the
     * rates it had then.
     */
    public List<Rate> applyingAsOf(String sheetId, LocalDate day) {
        return applying(sheetId, id -> changes.ratesAsOf(id, day));
    }

    /** The grade's rate that applies on the sheet on the date; empty with none. */
    public Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        List<String> lineage = lineage(sheetId);
        if (lineage.size() == 1) {
            return rates.findInForce(sheetId, grade, date);
        }
        return lineage.stream().map(id -> rates.findInForce(id, grade, date)).flatMap(Optional::stream).findFirst();
    }

    private List<Rate> applying(String sheetId, Function<String, List<Rate>> ratesOf) {
        List<String> lineage = lineage(sheetId);
        if (lineage.size() == 1) {
            return ratesOf.apply(sheetId);
        }
        List<Map<String, List<Rate>>> levels = new ArrayList<>();
        for (String id : lineage) {
            Map<String, List<Rate>> byGrade = new LinkedHashMap<>();
            for (Rate r : ratesOf.apply(id)) {
                byGrade.computeIfAbsent(r.getGrade(), g -> new ArrayList<>()).add(r);
            }
            levels.add(byGrade);
        }
        Set<String> grades = new TreeSet<>();
        levels.forEach(l -> grades.addAll(l.keySet()));
        List<Rate> applying = new ArrayList<>();
        for (String grade : grades) {
            applying.addAll(Rate.chained(sheetId, grade,
                    levels.stream().map(l -> l.getOrDefault(grade, List.of())).toList()));
        }
        return applying;
    }
}
