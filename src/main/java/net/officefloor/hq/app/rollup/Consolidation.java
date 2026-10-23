package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolCuts;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures of a sheet consolidated over it and all its descendants (the sheets naming it as their
 * parent, theirs, and so on), each sheet's as that sheet shows them now, or as it stood at the end of an
 * as-of date.
 */
@Service
public class Consolidation {

    /**
     * The number of people (those whose names or aliases match counted once), the people's due-totals and
     * overdue-totals over the whole tree added up, leaving out the single highest and single lowest when
     * there are three or more, per grade the due-totals of the
     * people not retired who have it in force on their sheet's today (those shown as n/a skipped) added
     * up, and per status how many claims have it.
     */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal,
            Map<String, BigDecimal> gradeDueTotals, Map<String, Integer> statusCounts) {
    }

    /** A sheet's figures with the people and claims they were read from. */
    private record Taken(Sheet sheet, Rollups.Rollup rollup, Map<Long, Person> people, Iterable<Claim> claims) {
    }

    private final SheetRepository sheets;
    private final Rollups rollups;
    private final SheetHistory history;
    private final Dues dues;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final PoolCuts cuts;

    public Consolidation(SheetRepository sheets, Rollups rollups, SheetHistory history, Dues dues,
            PersonRepository people, ClaimRepository claims, PoolCuts cuts) {
        this.cuts = cuts;
        this.sheets = sheets;
        this.rollups = rollups;
        this.history = history;
        this.dues = dues;
        this.people = people;
        this.claims = claims;
    }

    /** The sheet's figures added to those of all its descendants, each as it stands now. */
    @Transactional(readOnly = true)
    public Consolidated of(String sheetId) {
        return of(sheetId, null);
    }

    /**
     * The sheet's figures added to those of all its descendants (the tree as it is set now), each sheet's
     * as it stood at the end of an as-of date (null: as it stands now), with that date as its today.
     */
    @Transactional(readOnly = true)
    public Consolidated of(String sheetId, LocalDate asOf) {
        return of(sheetId, asOf, null);
    }

    /**
     * As {@link #of(String, LocalDate)}; with no as-of date but a preview day, each sheet's figures over its
     * records as they stand now, read as if its today were that day.
     */
    @Transactional(readOnly = true)
    public Consolidated of(String sheetId, LocalDate asOf, LocalDate preview) {
        List<String> all = new ArrayList<>();
        all.add(sheetId);
        all.addAll(sheets.descendants(sheetId));
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        List<Set<String>> persons = new ArrayList<>();
        Map<String, BigDecimal> grades = new TreeMap<>();
        Map<String, Integer> statuses = new TreeMap<>();
        for (String id : all) {
            Taken taken = asOf != null ? asOf(id, asOf) : preview != null ? preview(id, preview) : now(id);
            Rollups.Rollup rollup = taken.rollup();
            rollup.people().values().forEach(figures -> {
                if (figures.dueTotal() != null) {
                    dueFigures.add(figures.dueTotal());
                }
                overdueFigures.add(figures.overdueTotal());
            });
            rollup.people().keySet().forEach(personId -> {
                Person person = taken.people().get(personId);
                if (person != null) {
                    persons.add(keys(person));
                }
            });
            addGradeDueTotals(taken, grades);
            for (Claim claim : taken.claims()) {
                statuses.merge(claim.getStatus().name(), 1, Integer::sum);
            }
        }
        return new Consolidated(distinct(persons), Rollups.trimmed(dueFigures), Rollups.trimmed(overdueFigures),
                grades, statuses);
    }

    /**
     * The sheet's own summary due-total per grade, as it stands now: the due-totals of the people not
     * retired who have it in force on its today (those shown as n/a skipped) added up; a grade nobody has
     * is left out. Empty when no sheet has the id.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, BigDecimal>> gradeDueTotals(String sheetId) {
        if (sheets.findById(sheetId).isEmpty()) {
            return Optional.empty();
        }
        Map<String, BigDecimal> grades = new TreeMap<>();
        addGradeDueTotals(now(sheetId), grades);
        return Optional.of(grades);
    }

    /** Add a sheet's people's due-totals to the per-grade totals (see {@link Consolidated}). */
    private static void addGradeDueTotals(Taken taken, Map<String, BigDecimal> grades) {
        LocalDate today = taken.sheet() == null ? null : taken.sheet().getToday();
        taken.people().forEach((personId, person) -> {
            Rollups.Figures figures = taken.rollup().people().get(personId);
            if (person.getRetiredFrom() != null || today == null || figures == null) {
                return;
            }
            BigDecimal figure = figures.dueTotal() == null ? BigDecimal.ZERO.setScale(2) : figures.dueTotal();
            grades.merge(person.gradeOn(today), figure, BigDecimal::add);
        });
    }

    /** A person's name and aliases, each as names are compared. */
    private static Set<String> keys(Person person) {
        Set<String> keys = new HashSet<>();
        keys.add(Person.keyOf(person.getName()));
        person.getAliases().forEach(alias -> keys.add(Person.keyOf(alias)));
        return keys;
    }

    /**
     * How many people there are when those whose names or aliases match, as names are compared, are one
     * person (and so on through whoever they match in turn).
     */
    private static int distinct(List<Set<String>> persons) {
        List<Set<String>> groups = new ArrayList<>();
        for (Set<String> person : persons) {
            Set<String> merged = new HashSet<>(person);
            for (Iterator<Set<String>> it = groups.iterator(); it.hasNext();) {
                Set<String> group = it.next();
                if (!Collections.disjoint(group, merged)) {
                    merged.addAll(group);
                    it.remove();
                }
            }
            groups.add(merged);
        }
        return groups.size();
    }

    /** A sheet's figures as it stands now. */
    private Taken now(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LinkedHashMap<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(person -> sheetPeople.put(person.getId(), person));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        return new Taken(sheet, rollups.take(sheet, dues.live(sheetId), sheetPeople, sheetClaims,
                cuts.personCuts(sheet, sheetPeople.values())), sheetPeople, sheetClaims);
    }

    /** A sheet's figures over its records as they stand now, read as if its today were a preview day. */
    private Taken preview(String sheetId, LocalDate day) {
        Sheet now = sheets.findById(sheetId).orElse(null);
        if (now == null) {
            return now(sheetId);
        }
        Sheet then = now.asOf(day, now.getDated(), now.getPolicyDate());
        LinkedHashMap<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(person -> sheetPeople.put(person.getId(), person));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        return new Taken(then, rollups.take(then, rollups.previewBasis(then), sheetPeople, sheetClaims,
                cuts.personCuts(then, sheetPeople.values())), sheetPeople, sheetClaims);
    }

    /**
     * A sheet's figures as it stood at the end of a date, with that date as its today: before its today,
     * as kept for that date (none when nothing was kept by then); else as it stands.
     */
    private Taken asOf(String sheetId, LocalDate day) {
        Sheet now = sheets.findById(sheetId).orElse(null);
        if (now == null) {
            return now(sheetId);
        }
        if (day.isBefore(now.getToday())) {
            return history.at(sheetId, day)
                    .map(then -> new Taken(then.sheet(),
                            rollups.take(then.sheet(), then.basis(), then.people(), then.claims().values()),
                            then.people(), then.claims().values()))
                    .orElseGet(() -> {
                        Sheet empty = now.asOf(day, List.of(), null);
                        return new Taken(empty, rollups.take(empty, dues.live(sheetId), new LinkedHashMap<>(),
                                List.of()), Map.of(), List.of());
                    });
        }
        Sheet then = now.asOf(day, now.getDated(), now.getPolicyDate());
        Dues.Basis live = dues.live(sheetId);
        Dues.Basis basis = new Dues.Basis(then, live.rates(), history.inherited(sheetId, day), live.corrected());
        LinkedHashMap<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(person -> sheetPeople.put(person.getId(), person));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        return new Taken(then, rollups.take(then, basis, sheetPeople, sheetClaims), sheetPeople, sheetClaims);
    }
}
