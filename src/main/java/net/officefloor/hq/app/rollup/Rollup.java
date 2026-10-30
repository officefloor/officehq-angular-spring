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
import net.officefloor.hq.app.sheet.DatedSettings;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetSettingRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The figures of a sheet: each person's claimed, due-total and overdue-total, and the sheet's
 * claimed-total, due-total (a person showing n/a is skipped) and overdue-total, the last two leaving out the
 * single highest and single lowest person's figure when three or more people have one. A change takes the figures before it and, once done,
 * announces the ones it moved. Reads the store directly, within the change's own transaction.
 */
@Service
public class Rollup {

    /**
     * A person's figures as shown: claimed, due-total ({@code n/a} when there is none), and overdue-total
     * (the sum of the due figures of their overdue claims that count).
     */
    public record PersonFigures(String name, String claimed, String dueTotal, String overdueTotal) {

        boolean sameFigures(PersonFigures other) {
            return other != null && claimed.equals(other.claimed) && dueTotal.equals(other.dueTotal)
                    && overdueTotal.equals(other.overdueTotal);
        }
    }

    /** The sheet's claimed-total, due-total and overdue-total as shown. */
    public record Totals(String claimed, String dueTotal, String overdueTotal) {
    }

    /** Everyone's figures by person id, in the order they were added, and the sheet's totals. */
    public record Figures(Map<Long, PersonFigures> people, Totals totals) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final SheetSettingRepository settings;
    private final ApplicationEventPublisher events;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            SheetSettingRepository settings, ApplicationEventPublisher events) {
        this.settings = settings;
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
                new DatedSettings(settings.findBySheetId(sheetId)), sheet == null ? null : sheet.getPolicyDate());
        Map<Long, List<Claim>> bySheet = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, PersonFigures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> theirs = bySheet.getOrDefault(person.getId(), List.of());
            BigDecimal claimed = theirs.stream().filter(c -> Dues.counts(c, person::heldOn, today)).map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal due = Dues.total(person::gradeOn, theirs, table, person::heldOn);
            BigDecimal overdue = Dues.sum(person::gradeOn, Dues.overdue(theirs, table, person::heldOn), table);
            claimedTotal = claimedTotal.add(claimed);
            overdues.add(overdue.setScale(2, RoundingMode.HALF_UP));
            if (due != null) {
                dues.add(due.setScale(2, RoundingMode.HALF_UP));
            }
            figures.put(person.getId(), new PersonFigures(person.getName(), money(claimed),
                    due == null ? "n/a" : money(due), money(overdue)));
        }
        return new Figures(figures, new Totals(money(claimedTotal), money(trimmedSum(dues)), money(trimmedSum(overdues))));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    private static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        return sum.subtract(figures.stream().max(BigDecimal::compareTo).orElseThrow())
                .subtract(figures.stream().min(BigDecimal::compareTo).orElseThrow());
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
