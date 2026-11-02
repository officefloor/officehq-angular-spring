package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures a sheet rolls up to: what each person has claimed and their due-total, and the
 * sheet's totals of both. A change made through {@link #tracking} is followed in the audit by one
 * ROLLUP line per person whose figures it moved (in the order they were added), then a TOTALS line
 * when the sheet's totals moved.
 */
@Service
public class Rollup {

    /** A person's figures as two-decimal text; a due-total of n/a is "n/a". */
    private record Figures(String name, String claimed, String dueTotal) {
        boolean sameAs(Figures other) {
            return other != null && claimed.equals(other.claimed) && dueTotal.equals(other.dueTotal);
        }
    }

    /** The figures of a sheet: each person's by id in the order they were added, and the totals. */
    private record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final Dues dues;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, Dues dues, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.dues = dues;
        this.audit = audit;
    }

    /** Make a change on the sheet, then record in the audit, after the change's own records, the
     *  figures it moved. A change that is refused records nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T tracking(String sheetId, String user, Supplier<T> change) {
        Snapshot before = snapshot(sheetId);
        T result = change.get();
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        after.people().forEach((id, figures) -> {
            if (!figures.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + figures.name()
                        + " claimed=" + figures.claimed() + " due-total=" + figures.dueTotal());
            }
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal());
        }
        return result;
    }

    private Snapshot snapshot(String sheetId) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal dueTotal = BigDecimal.ZERO.setScale(2);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> theirs = Dues.live(person, byPerson.getOrDefault(person.getId(), List.of()));
            BigDecimal claimed = theirs.stream().map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            BigDecimal due = dues.dueTotal(sheetId, person, theirs);
            claimedTotal = claimedTotal.add(claimed);
            if (due != null) {
                dueTotal = dueTotal.add(due);
            }
            figures.put(person.getId(), new Figures(person.getName(), text(claimed),
                    due == null ? "n/a" : text(due)));
        }
        return new Snapshot(figures, text(claimedTotal), text(dueTotal));
    }

    private static String text(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
