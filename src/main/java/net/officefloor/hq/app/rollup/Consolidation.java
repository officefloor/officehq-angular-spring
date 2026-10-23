package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
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
     * The number of people, the due-totals and overdue-totals added up, per grade the due-totals of the
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

    public Consolidation(SheetRepository sheets, Rollups rollups, SheetHistory history, Dues dues,
            PersonRepository people, ClaimRepository claims) {
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
        List<String> all = new ArrayList<>();
        all.add(sheetId);
        all.addAll(sheets.descendants(sheetId));
        int count = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        Map<String, BigDecimal> grades = new TreeMap<>();
        Map<String, Integer> statuses = new TreeMap<>();
        for (String id : all) {
            Taken taken = asOf == null ? now(id) : asOf(id, asOf);
            Rollups.Rollup rollup = taken.rollup();
            count += rollup.people().size();
            due = due.add(rollup.dueTotal());
            overdue = overdue.add(rollup.overdueTotal());
            LocalDate today = taken.sheet() == null ? null : taken.sheet().getToday();
            taken.people().forEach((personId, person) -> {
                Rollups.Figures figures = rollup.people().get(personId);
                if (person.getRetiredFrom() != null || today == null || figures == null) {
                    return;
                }
                BigDecimal figure = figures.dueTotal() == null ? BigDecimal.ZERO.setScale(2) : figures.dueTotal();
                grades.merge(person.gradeOn(today), figure, BigDecimal::add);
            });
            for (Claim claim : taken.claims()) {
                statuses.merge(claim.getStatus().name(), 1, Integer::sum);
            }
        }
        return new Consolidated(count, due, overdue, grades, statuses);
    }

    /** A sheet's figures as it stands now. */
    private Taken now(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LinkedHashMap<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(person -> sheetPeople.put(person.getId(), person));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        return new Taken(sheet, rollups.take(sheet, dues.live(sheetId), sheetPeople, sheetClaims), sheetPeople,
                sheetClaims);
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
