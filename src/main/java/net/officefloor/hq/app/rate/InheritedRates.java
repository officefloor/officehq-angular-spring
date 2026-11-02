package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.sheet.SheetParent;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetSource;
import net.officefloor.hq.app.sheet.SheetSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rates a sheet's figures use: its own, then, for a grade and date it has none in force on, its
 * parent's, its parent's parent's, up to {@link SheetParent#LEVELS} levels up; then, for a clone, those of
 * the sheet it was cloned from ({@link SheetSource}) in the same way: its own, its parents', its source's.
 */
@Service
public class InheritedRates {

    private final RateRepository rates;
    private final RateLogRepository log;
    private final SheetParentRepository parents;
    private final SheetSourceRepository sources;

    public InheritedRates(RateRepository rates, RateLogRepository log, SheetParentRepository parents,
            SheetSourceRepository sources) {
        this.rates = rates;
        this.log = log;
        this.parents = parents;
        this.sources = sources;
    }

    /**
     * The sheets whose rates the sheet takes, nearest first: its parents (up to {@link SheetParent#LEVELS}
     * levels up), then the sheet it was cloned from, that sheet's parents, its source, and so on; each once.
     */
    private List<String> inheritedFrom(String sheetId) {
        List<String> from = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        for (String at = sheetId; at != null; ) {
            for (String ancestor : parents.ancestorsOf(at, SheetParent.LEVELS)) if (seen.add(ancestor)) from.add(ancestor);
            String source = sources.sourceOf(at);
            if (source == null || !seen.add(source)) break;
            from.add(source);
            at = source;
        }
        return from;
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
        for (String ancestor : inheritedFrom(sheetId)) {
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
        for (String ancestor : inheritedFrom(sheetId)) levels.add(asOf(ancestor, date));
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
