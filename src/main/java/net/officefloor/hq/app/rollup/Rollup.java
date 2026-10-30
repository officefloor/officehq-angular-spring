package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The figures of a sheet: each person's claimed and due-total, and the sheet's claimed-total and
 * due-total (a person showing n/a is skipped). A change takes the figures before it and, once done,
 * announces the ones it moved. Reads the store directly, within the change's own transaction.
 */
@Service
public class Rollup {

    /** A person's figures as shown: claimed, and due-total ({@code n/a} when there is none). */
    public record PersonFigures(String name, String claimed, String dueTotal) {

        boolean sameFigures(PersonFigures other) {
            return other != null && claimed.equals(other.claimed) && dueTotal.equals(other.dueTotal);
        }
    }

    /** The sheet's claimed-total and due-total as shown. */
    public record Totals(String claimed, String dueTotal) {
    }

    /** Everyone's figures by person id, in the order they were added, and the sheet's totals. */
    public record Figures(Map<Long, PersonFigures> people, Totals totals) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final ApplicationEventPublisher events;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            ApplicationEventPublisher events) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.events = events;
    }

    /** The sheet's figures now. */
    public Figures figures(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? LocalDate.now() : sheet.getToday();
        RateTable table = new RateTable(rates.findBySheetId(sheetId), today,
                sheet == null ? Sheet.DEFAULT_FLOOR : sheet.getFloor(),
                sheet == null ? Sheet.DEFAULT_LATE_PCT : sheet.getLatePct(),
                sheet == null ? null : sheet.getPolicyDate(), sheet == null ? null : sheet.getCap());
        Map<Long, List<Claim>> bySheet = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, PersonFigures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO;
        BigDecimal dueTotal = BigDecimal.ZERO;
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> theirs = bySheet.getOrDefault(person.getId(), List.of());
            BigDecimal claimed = theirs.stream().filter(c -> Dues.counts(c, person::heldOn)).map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal due = Dues.total(person::gradeOn, theirs, table, person::heldOn);
            claimedTotal = claimedTotal.add(claimed);
            if (due != null) {
                dueTotal = dueTotal.add(due);
            }
            figures.put(person.getId(), new PersonFigures(person.getName(), money(claimed),
                    due == null ? "n/a" : money(due)));
        }
        return new Figures(figures, new Totals(money(claimedTotal), money(dueTotal)));
    }

    /**
     * Announce the figures the change moved since {@code before}: each person whose figures changed (one
     * who did not exist before counting as changed), then the totals when they changed. Nothing when no
     * figure moved.
     */
    public void changed(String sheetId, String user, Figures before) {
        Figures after = figures(sheetId);
        List<PersonFigures> moved = new ArrayList<>();
        after.people().forEach((id, now) -> {
            if (!now.sameFigures(before.people().get(id))) {
                moved.add(now);
            }
        });
        Totals totals = Objects.equals(before.totals(), after.totals()) ? null : after.totals();
        if (!moved.isEmpty() || totals != null) {
            events.publishEvent(new RollupChanged(sheetId, user, moved, totals));
        }
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
