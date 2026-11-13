package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The figures a sheet rolls up to: each person's claimed and due-total, and the sheet's totals. A
 *  change made through {@link #track} is followed in the audit by a line for each person whose
 *  figures it moved, then one for the sheet's totals when they moved. */
@Service
public class RollupService {

    /** Written for a due-total that has no rate to work from. */
    private static final String NO_RATE = "n/a";

    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

    /** A person's figures; due is null when it has no rate to work from. */
    private record Figures(String name, BigDecimal claimed, BigDecimal due) {
        boolean sameAs(Figures other) {
            return other != null && claimed.compareTo(other.claimed) == 0 && Objects.equals(due, other.due);
        }
    }

    /** The sheet's figures: each person's, by id in the order they were added, and the totals. */
    private record Rollup(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateService rates;
    private final SheetService sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimService claimService,
            RateService rates, SheetService sheets, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Make a change to the sheet, then record in the audit the figures it moved. The sheet is held
     *  locked throughout, so a change made at the same moment waits and the figures are its own. */
    @Transactional
    public <T> T track(String sheetId, String user, Supplier<T> change) {
        sheets.lock(sheetId);
        Rollup before = rollup(sheetId);
        T result = change.get();
        Rollup after = rollup(sheetId);
        after.people().forEach((id, figures) -> {
            if (!figures.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                        + " claimed=" + figures.claimed().toPlainString() + " due-total=" + text(figures.due()));
            }
        });
        if (after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user
                    + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString());
        }
        return result;
    }

    private Rollup rollup(String sheetId) {
        LocalDate today = sheets.today(sheetId);
        Map<Long, List<Claim>> counted = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(Claim::counts)
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = NONE;
        BigDecimal dueTotal = NONE;
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            Figures f = figuresOf(sheetId, today, person, counted.getOrDefault(person.getId(), List.of()));
            figures.put(person.getId(), f);
            claimedTotal = claimedTotal.add(f.claimed());
            if (f.due() != null) {
                dueTotal = dueTotal.add(f.due());
            }
        }
        return new Rollup(figures, claimedTotal, dueTotal);
    }

    /** A person's figures from their claims that count: the sum of their amounts, and the sum of the
     *  figures they show, or none when the grade has no rate in force on the sheet's today and no
     *  settled or stated claim of theirs keeps a figure. */
    private Figures figuresOf(String sheetId, LocalDate today, Person person, List<Claim> counted) {
        BigDecimal claimed = NONE;
        BigDecimal due = NONE;
        boolean anyFigure = false;
        for (Claim claim : counted) {
            claimed = claimed.add(claim.getAmount());
            BigDecimal figure = claimService.figureOf(sheetId, person, claim);
            if (figure != null) {
                due = due.add(figure);
            }
            anyFigure |= claim.getSettledDue() != null || (claim.isStated() && claim.getStatedDue() != null);
        }
        boolean hasRate = rates.rateOn(sheetId, person.getGrade(), today).isPresent();
        return new Figures(person.getName(), claimed, hasRate || anyFigure ? due : null);
    }

    private static String text(BigDecimal due) {
        return due == null ? NO_RATE : due.toPlainString();
    }
}
