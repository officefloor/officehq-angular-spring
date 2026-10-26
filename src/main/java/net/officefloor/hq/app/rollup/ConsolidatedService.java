package net.officefloor.hq.app.rollup;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.SheetParentLink;
import net.officefloor.hq.app.setting.SheetParentRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures of a sheet's descendants (its children, the sheets naming it as their parent, then
 * theirs, and so on), added up: the number of people on them and each one's due-total and
 * overdue-total as that sheet shows it; per grade, the due-totals of the people on it today who are not
 * retired (skipping n/a); and per status, how many claims have it. The screen adds the sheet's own figures to make its
 * consolidated figures. A sheet with no children has none. The sheet's own figures are answered too, as
 * it shows them. Read as of a date (within the as-of view's transaction, which is rolled back), every
 * sheet of the tree is put back to how it stood at the end of that day, with that date as its today.
 */
@Service
public class ConsolidatedService {

    /** The descendants, in the order found (nearest first), and their figures added up, and the sheet's own;
     *  the totals with two decimals. The grade due-totals by grade, every grade listed; the status counts by
     *  status, every status but DELETED listed. */
    public record Descendants(List<String> sheets, int peopleCount, String dueTotal, String overdueTotal,
            int ownPeopleCount, String ownDueTotal, String ownOverdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts) {
    }

    private final SheetParentRepository parents;
    private final Rollup rollup;
    private final SheetHistory history;
    private final EntityManager entityManager;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final SheetRepository sheets;

    public ConsolidatedService(SheetParentRepository parents, Rollup rollup, SheetHistory history,
            EntityManager entityManager, PersonRepository people, ClaimRepository claims, SheetRepository sheets) {
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.parents = parents;
        this.rollup = rollup;
        this.history = history;
        this.entityManager = entityManager;
    }

    /** The descendants as the store holds them now, or with the date, as every sheet of the tree stood at
     *  the end of that day: only within a transaction that is rolled back (the as-of view's). */
    @Transactional(readOnly = true)
    public Descendants descendantsOf(String sheetId, LocalDate asOf) {
        Set<String> found = asOf == null ? descendantsNow(sheetId) : descendantsAsOf(sheetId, asOf);
        int peopleCount = 0;
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        Map<Grade, BigDecimal> gradeDue = new LinkedHashMap<>();
        for (Grade grade : Grade.values()) gradeDue.put(grade, BigDecimal.ZERO);
        Map<ClaimStatus, Integer> statusCount = new LinkedHashMap<>();
        for (ClaimStatus status : ClaimStatus.values()) {
            if (status != ClaimStatus.DELETED) statusCount.put(status, 0);
        }
        for (String sheet : found) {
            Rollup.Snapshot figures = rollup.snapshot(sheet);
            peopleCount += figures.people().size();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
            LocalDate today = sheets.findById(sheet).map(Sheet::getToday).orElse(null);
            for (Person person : people.findInListOrder(sheet)) {
                if (person.isRetired() || today == null) continue;
                Grade grade = person.gradeOn(today);
                Rollup.Figures theirs = figures.people().get(person.getId());
                if (grade == null || theirs == null || theirs.dueTotal() == null) continue;
                gradeDue.merge(grade, theirs.dueTotal(), BigDecimal::add);
            }
            for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheet)) {
                statusCount.computeIfPresent(claim.getStatus(), (s, n) -> n + 1);
            }
        }
        Map<String, String> gradeDueTotals = new LinkedHashMap<>();
        gradeDue.forEach((grade, total) -> gradeDueTotals.put(grade.name(), text(total)));
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        statusCount.forEach((status, n) -> statusCounts.put(status.name(), n));
        Rollup.Snapshot own = rollup.snapshot(sheetId);
        return new Descendants(new ArrayList<>(found), peopleCount, text(due), text(overdue), own.people().size(),
                text(own.dueTotal()), text(own.overdueTotal()), gradeDueTotals, statusCounts);
    }

    private Set<String> descendantsNow(String sheetId) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(sheetId));
        while (!next.isEmpty()) {
            for (SheetParentLink child : parents.findByParentId(next.pop())) {
                if (!child.getSheetId().equals(sheetId) && found.add(child.getSheetId())) next.add(child.getSheetId());
            }
        }
        return found;
    }

    /** Each sheet naming a sheet of the tree as its parent is put back to the date, and is a descendant
     *  when, as it stood then, it named that sheet. */
    private Set<String> descendantsAsOf(String sheetId, LocalDate asOf) {
        Set<String> found = new LinkedHashSet<>();
        Set<String> restored = new LinkedHashSet<>(List.of(sheetId));
        Deque<String> next = new ArrayDeque<>(List.of(sheetId));
        while (!next.isEmpty()) {
            String parent = next.pop();
            for (String child : history.childrenOf(parent)) {
                if (found.contains(child) || child.equals(sheetId)) continue;
                if (restored.add(child)) history.restoreWithParents(child, asOf);
                if (history.namesParent(child, parent)) {
                    found.add(child);
                    next.add(child);
                }
            }
        }
        // The store was put back beneath the persistence context: what it holds is read afresh.
        entityManager.clear();
        return found;
    }

    private static String text(BigDecimal figure) {
        return figure.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
