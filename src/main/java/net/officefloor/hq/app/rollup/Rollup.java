package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures a sheet rolls up to: what each person has claimed, their due-total and their
 * overdue-total, and the sheet's totals of all three. The sheet's due-total and overdue-total leave
 * out the single highest and the single lowest person's figure when three or more people have one
 * (a due-total of n/a is no figure). A change made through {@link #tracking} is followed in the audit by one
 * ROLLUP line per person whose figures it moved (in the order they were added), then a TOTALS line
 * when the sheet's totals moved; and the sheet's {@link History} records what it changed.
 */
@Service
public class Rollup {

    /** A person's figures as two-decimal text; a due-total of n/a is "n/a". */
    private record Figures(String name, String claimed, String dueTotal, String overdueTotal) {
        boolean sameAs(Figures other) {
            return other != null && claimed.equals(other.claimed) && dueTotal.equals(other.dueTotal)
                    && overdueTotal.equals(other.overdueTotal);
        }
    }

    /** The figures of a sheet: each person's by id in the order they were added, and the totals. */
    private record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal,
            String overdueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final Dues dues;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final History history;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, Dues dues, SheetService sheets,
            SheetRepository sheetStore, History history, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.dues = dues;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.history = history;
        this.audit = audit;
    }

    /** Make a change on the sheet, then record in the audit, after the change's own records, the
     *  figures it moved, and in the sheet's history what it changed. A change that is refused records
     *  nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T tracking(String sheetId, String user, Supplier<T> change) {
        Snapshot before = snapshot(sheetId);
        T result = change.get();
        history.record(sheetId);
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        after.people().forEach((id, figures) -> {
            if (!figures.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + figures.name()
                        + " claimed=" + figures.claimed() + " due-total=" + figures.dueTotal()
                        + " overdue-total=" + figures.overdueTotal());
            }
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())
                || !Objects.equals(before.overdueTotal(), after.overdueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal() + " overdue-total=" + after.overdueTotal());
        }
        return result;
    }

    /** Record in the audit one SNAPSHOT line per person (in the order they were added) with their
     *  due-total as it stands now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSnapshot(String sheetId, String user) {
        String prefix = " sheet=" + sheetId + " by=" + user;
        snapshot(sheetId).people().values().forEach(figures -> audit.recordOnCommit(
                "SNAPSHOT" + prefix + " name=" + figures.name() + " due-total=" + figures.dueTotal()));
    }

    /** The figures a sheet's descendants add to its own to consolidate it: over all of them, the
     *  people (retired included), and each one's due-total and overdue-total as that sheet shows
     *  them, added up. */
    public record Descendants(int peopleCount, String dueTotal, String overdueTotal) {
    }

    /** The figures of a sheet's descendants added up (all zero when it has none). */
    @Transactional(readOnly = true)
    public Descendants descendants(String sheetId) {
        int count = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        for (String id : sheetStore.descendants(sheetId)) {
            Snapshot s = snapshot(id);
            count += s.people().size();
            due = due.add(new BigDecimal(s.dueTotal()));
            overdue = overdue.add(new BigDecimal(s.overdueTotal()));
        }
        return new Descendants(count, text(due), text(overdue));
    }

    private Snapshot snapshot(String sheetId) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        LocalDate today = sheets.today(sheetId);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> theirs = Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()), today);
            BigDecimal claimed = theirs.stream().map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            BigDecimal due = dues.dueTotal(sheetId, person, theirs);
            BigDecimal overdue = theirs.stream().filter(c -> dues.overdue(sheetId, person, c))
                    .map(c -> dues.figure(sheetId, person, c)).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            claimedTotal = claimedTotal.add(claimed);
            overdueFigures.add(overdue);
            if (due != null) {
                dueFigures.add(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), text(claimed),
                    due == null ? "n/a" : text(due), text(overdue)));
        }
        return new Snapshot(figures, text(claimedTotal), text(trimmedSum(dueFigures)), text(trimmedSum(overdueFigures)));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are
     *  three or more. */
    private static BigDecimal trimmedSum(List<BigDecimal> figures) {
        List<BigDecimal> kept = new ArrayList<>(figures);
        if (kept.size() >= 3) {
            kept.sort(Comparator.naturalOrder());
            kept = kept.subList(1, kept.size() - 1);
        }
        return kept.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static String text(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
