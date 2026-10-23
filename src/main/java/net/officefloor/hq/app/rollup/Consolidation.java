package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** The number of people, and the due-totals and overdue-totals added up. */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal) {
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
        for (String id : all) {
            Rollups.Rollup rollup = asOf == null ? rollups.take(id) : asOf(id, asOf);
            count += rollup.people().size();
            due = due.add(rollup.dueTotal());
            overdue = overdue.add(rollup.overdueTotal());
        }
        return new Consolidated(count, due, overdue);
    }

    /**
     * A sheet's figures as it stood at the end of a date, with that date as its today: before its today,
     * as kept for that date (none when nothing was kept by then); else as it stands.
     */
    private Rollups.Rollup asOf(String sheetId, LocalDate day) {
        Sheet now = sheets.findById(sheetId).orElse(null);
        if (now == null) {
            return rollups.take(sheetId);
        }
        if (day.isBefore(now.getToday())) {
            return history.at(sheetId, day)
                    .map(then -> rollups.take(then.sheet(), then.basis(), then.people(), then.claims().values()))
                    .orElseGet(() -> rollups.take(now.asOf(day, List.of(), null), dues.live(sheetId),
                            new LinkedHashMap<>(), List.of()));
        }
        Sheet then = now.asOf(day, now.getDated(), now.getPolicyDate());
        Dues.Basis live = dues.live(sheetId);
        Dues.Basis basis = new Dues.Basis(then, live.rates(), history.inherited(sheetId, day), live.corrected());
        LinkedHashMap<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(person -> sheetPeople.put(person.getId(), person));
        return rollups.take(then, basis, sheetPeople, claims.findBySheetIdOrderByIdDesc(sheetId));
    }
}
