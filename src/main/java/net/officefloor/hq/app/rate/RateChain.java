package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * The rates that apply on the sheet to the claims added up to a mark (inclusive; null: with no end) and after the
     * previous tier's: forward-only rates saved at a greater mark leave them out (see {@link Rate#appliesTo}).
     */
    public record Tier(Long upTo, List<Rate> rates) {
    }

    private final RateRepository rates;
    private final RateChangeRepository changes;
    private final RateVersionRepository versions;
    private final SheetRepository sheets;

    public RateChain(RateRepository rates, RateChangeRepository changes, RateVersionRepository versions,
            SheetRepository sheets) {
        this.rates = rates;
        this.changes = changes;
        this.versions = versions;
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

    /**
     * The rates that apply on the sheet by the claim mark they apply to, as tiers in mark order, the last (with no
     * end) the rates as they apply to claims added from now on: every grade's rates as they apply on the sheet.
     */
    public List<Tier> tiers(String sheetId) {
        return tiers(sheetId, this::versions);
    }

    /** The tiers of the rates as they applied on the sheet at the end of the day (see {@link #applyingAsOf}). */
    public List<Tier> tiersAsOf(String sheetId, LocalDate day) {
        return tiers(sheetId, id -> changes.versionsAsOf(id, day));
    }

    /** Among the tiers, the rates that apply to a claim added at the mark. */
    public static List<Rate> forMark(List<Tier> tiers, long mark) {
        return tiers.stream().filter(t -> t.upTo() == null || mark <= t.upTo()).findFirst().map(Tier::rates)
                .orElse(List.of());
    }

    /**
     * The grade's rate that applies on the sheet on the date to a claim added at the mark: forward-only rates saved
     * after it are left out; empty with none.
     */
    public Optional<Rate> findInForce(String sheetId, String grade, LocalDate date, long mark) {
        List<Rate> seen = applying(sheetId, id -> Rate.seenBy(versions(id), mark)).stream()
                .filter(r -> r.getGrade().equals(grade)).toList();
        return Optional.ofNullable(Rate.inForce(seen, date));
    }

    /** One sheet's rate versions, oldest first: each rate's older versions, then its newest. */
    private List<Rate> versions(String sheetId) {
        Map<Long, List<RateVersion>> older = new LinkedHashMap<>();
        for (RateVersion v : versions.findBySheet(sheetId)) {
            older.computeIfAbsent(v.getRateId(), id -> new ArrayList<>()).add(v);
        }
        List<Rate> all = new ArrayList<>();
        for (Rate r : rates.findBySheetIdOrderByGradeAscFromAsc(sheetId)) {
            for (RateVersion v : older.getOrDefault(r.getId(), List.of())) {
                Rate was = new Rate(sheetId, r.getGrade(), r.getFrom(), v.getPct());
                was.setTo(v.getTo());
                was.setAfterMark(v.getAfterMark());
                all.add(was);
            }
            all.add(r);
        }
        return all;
    }

    private List<Tier> tiers(String sheetId, Function<String, List<Rate>> versionsOf) {
        Map<String, List<Rate>> byLevel = new LinkedHashMap<>();
        for (String id : lineage(sheetId)) {
            byLevel.put(id, versionsOf.apply(id));
        }
        TreeSet<Long> marks = new TreeSet<>();
        byLevel.values().forEach(l -> l.stream().map(Rate::getAfterMark).filter(Objects::nonNull).forEach(marks::add));
        List<Tier> tiers = new ArrayList<>();
        for (Long mark : marks) {
            tiers.add(new Tier(mark, applying(sheetId, id -> Rate.seenBy(byLevel.get(id), mark))));
        }
        tiers.add(new Tier(null, applying(sheetId, id -> Rate.seenBy(byLevel.get(id), Long.MAX_VALUE))));
        return tiers;
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
