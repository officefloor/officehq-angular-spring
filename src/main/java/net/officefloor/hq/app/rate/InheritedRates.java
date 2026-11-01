package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.sheet.SheetParent;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rates a sheet's figures use: its own, then, for a grade and date it has none in force on, its
 * parent's, its parent's parent's, up to {@link SheetParent#LEVELS} levels up.
 */
@Service
public class InheritedRates {

    private final RateRepository rates;
    private final RateLogRepository log;
    private final SheetParentRepository parents;

    public InheritedRates(RateRepository rates, RateLogRepository log, SheetParentRepository parents) {
        this.rates = rates;
        this.log = log;
        this.parents = parents;
    }

    /** The sheet's rates and its ancestors', as they are now. */
    @Transactional(readOnly = true)
    public RateLevels of(String sheetId) {
        List<Map<String, List<Rate>>> levels = new ArrayList<>();
        levels.add(byGrade(rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId)));
        levels.addAll(ancestors(sheetId));
        return new RateLevels(levels);
    }

    /** The rates of the sheet's ancestors, nearest first, each by grade, as they are now. */
    @Transactional(readOnly = true)
    public List<Map<String, List<Rate>>> ancestors(String sheetId) {
        List<Map<String, List<Rate>>> levels = new ArrayList<>();
        for (String ancestor : parents.ancestorsOf(sheetId, SheetParent.LEVELS)) {
            levels.add(byGrade(rates.findBySheetIdOrderByGradeAscFromDateAsc(ancestor)));
        }
        return levels;
    }

    /** The sheet's rates and its ancestors', each as they stood at the end of the date ({@link #asOf(String, LocalDate)}). */
    @Transactional(readOnly = true)
    public RateLevels levelsAsOf(String sheetId, LocalDate date) {
        List<Map<String, List<Rate>>> levels = new ArrayList<>();
        levels.add(asOf(sheetId, date));
        levels.addAll(ancestorsAsOf(sheetId, date));
        return new RateLevels(levels);
    }

    /** The rates of the sheet's ancestors, nearest first, each by grade as they stood at the end of the date. */
    @Transactional(readOnly = true)
    public List<Map<String, List<Rate>>> ancestorsAsOf(String sheetId, LocalDate date) {
        List<Map<String, List<Rate>>> levels = new ArrayList<>();
        for (String ancestor : parents.ancestorsOf(sheetId, SheetParent.LEVELS)) levels.add(asOf(ancestor, date));
        return levels;
    }

    /**
     * A sheet's rates by grade as they stood at the end of the date: of those set while its today was
     * on or before it, the last set for each grade and from-date (its newest version then).
     */
    @Transactional(readOnly = true)
    public Map<String, List<Rate>> asOf(String sheetId, LocalDate date) {
        Map<String, Map<Optional<LocalDate>, Rate>> then = new LinkedHashMap<>();
        for (RateLog line : log.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!line.setBy(date)) continue;
            Map<Optional<LocalDate>, Rate> grade = then.computeIfAbsent(line.getGrade(), g -> new LinkedHashMap<>());
            Optional<LocalDate> from = Optional.ofNullable(line.getFromDate());
            Rate replaced = grade.get(from);
            grade.put(from, line.toRate(replaced == null ? 1 : replaced.getVersions() + 1));
        }
        Map<String, List<Rate>> byGrade = new LinkedHashMap<>();
        then.forEach((grade, rates) -> byGrade.put(grade, new ArrayList<>(rates.values())));
        return byGrade;
    }

    private static Map<String, List<Rate>> byGrade(List<Rate> list) {
        Map<String, List<Rate>> byGrade = new LinkedHashMap<>();
        for (Rate rate : list) byGrade.computeIfAbsent(rate.getGrade(), g -> new ArrayList<>()).add(rate);
        return byGrade;
    }
}
