package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonHold;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The figures a sheet rolls up to: each person's claimed, due-total and overdue-total, and the sheet's totals. A
 *  change made through {@link #track} is followed in the audit by a line for each person whose
 *  figures it moved, then one for the sheet's totals when they moved, then an alert for each person
 *  whose due-total it took above the sheet's alert threshold. */
@Service
public class RollupService {

    /** Written for a due-total that has no rate to work from. */
    private static final String NO_RATE = "n/a";

    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

    /** A person's figures; due is null when it has no rate to work from. */
    private record Figures(String name, BigDecimal claimed, BigDecimal due, BigDecimal overdue) {
        boolean sameAs(Figures other) {
            return other != null && claimed.compareTo(other.claimed) == 0 && Objects.equals(due, other.due)
                    && overdue.compareTo(other.overdue) == 0;
        }
    }

    /** The sheet's figures: each person's, in list order, and the totals. */
    private record Rollup(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal) {
    }

    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateService rates;
    private final PersonGradeService grades;
    private final PersonService personService;
    private final SheetService sheets;
    private final SettingService settings;
    private final History history;
    private final Audit audit;

    public RollupService(ClaimRepository claims, ClaimService claimService,
            RateService rates, PersonGradeService grades, PersonService personService, SheetService sheets,
            SettingService settings, History history, Audit audit) {
        this.history = history;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.grades = grades;
        this.personService = personService;
        this.sheets = sheets;
        this.settings = settings;
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
                audit.recordOnCommit(rollupLine(sheetId, user, figures));
            }
        });
        if (after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0
                || after.overdueTotal().compareTo(before.overdueTotal()) != 0) {
            audit.recordOnCommit(totalsLine(sheetId, user, after));
        }
        BigDecimal threshold = settings.alertThreshold(sheetId);
        if (threshold != null) {
            after.people().forEach((id, figures) -> {
                Figures was = before.people().get(id);
                if (above(figures.due(), threshold) && (was == null || !above(was.due(), threshold))) {
                    audit.recordOnCommit("ALERT sheet=" + sheetId + " by=" + user + " name=" + figures.name()
                            + " due-total=" + figures.due().toPlainString());
                }
            });
        }
        return result;
    }

    /** Record in the audit every person's figures as they stand now, in list order, then the sheet's
     *  totals: the figures a sheet starts from when it is made whole (a clone). */
    @Transactional
    public void recordAll(String sheetId, String user) {
        Rollup now = rollup(sheetId);
        now.people().values().forEach(figures -> audit.recordOnCommit(rollupLine(sheetId, user, figures)));
        audit.recordOnCommit(totalsLine(sheetId, user, now));
    }

    /** Whether a due-total is above the alert threshold; none (n/a) never is. */
    private static boolean above(BigDecimal due, BigDecimal threshold) {
        return due != null && due.compareTo(threshold) > 0;
    }

    private static String rollupLine(String sheetId, String user, Figures figures) {
        return "ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                + " claimed=" + figures.claimed().toPlainString() + " due-total=" + text(figures.due())
                + " overdue-total=" + figures.overdue().toPlainString();
    }

    private static String totalsLine(String sheetId, String user, Rollup rollup) {
        return "TOTALS sheet=" + sheetId + " by=" + user
                + " claimed-total=" + rollup.claimedTotal().toPlainString()
                + " due-total=" + rollup.dueTotal().toPlainString()
                + " overdue-total=" + rollup.overdueTotal().toPlainString();
    }

    /** A sheet's headline figures as it shows them now: how many people are on it, and its due-total
     *  and overdue-total. */
    public record Headline(int people, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** The sheet's headline figures as it shows them now. */
    @Transactional(readOnly = true)
    public Headline headline(String sheetId) {
        return headline(sheetId, null);
    }

    /** The sheet's headline figures as it stood at the end of a day, that day standing in for the
     *  sheet's today (null: as it shows them now). */
    @Transactional(readOnly = true)
    public Headline headline(String sheetId, LocalDate asOf) {
        Rollup rollup = rollup(sheetId, asOf);
        return new Headline(rollup.people().size(), rollup.dueTotal(), rollup.overdueTotal());
    }

    /** Each person's due-total on the sheet as it shows it now ("n/a" for none), by person id in list order. */
    @Transactional(readOnly = true)
    public Map<Long, String> dueTotals(String sheetId) {
        Map<Long, String> dues = new LinkedHashMap<>();
        rollup(sheetId).people().forEach((personId, figures) -> dues.put(personId, text(figures.due())));
        return dues;
    }

    /** Record in the audit each person's due-total as it stands now, in list order: the figures of
     *  the day the sheet's today is about to leave; each is kept for the person with that day and the
     *  day the sheet's today is moving to. */
    public void snapshot(String sheetId, String user, LocalDate movingTo) {
        LocalDate leaving = sheets.today(sheetId);
        rollup(sheetId).people().forEach((personId, figures) -> {
            personService.snapshot(sheetId, personId, leaving, text(figures.due()), movingTo);
            audit.recordOnCommit("SNAPSHOT sheet=" + sheetId + " by=" + user + " name=" + figures.name()
                    + " due-total=" + text(figures.due()));
        });
    }

    private Rollup rollup(String sheetId) {
        return rollup(sheetId, null);
    }

    /** The sheet's figures as it stood at the end of a day: its people, claims, holds, grades, rates
     *  and settings as they stood then, the day standing in for the sheet's today (null: now). */
    private Rollup rollup(String sheetId, LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        List<Person> sheetPeople = personService.list(sheetId, asOf);
        Map<Long, List<PersonHold>> holds = personService.holds(sheetId, asOf);
        Map<Long, LocalDate> retiredFrom = new HashMap<>();
        sheetPeople.forEach(person -> retiredFrom.put(person.getId(), person.getRetiredFrom()));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDesc(sheetId);
        Map<Long, List<Claim>> counted = (asOf == null ? sheetClaims : history.claims(sheetClaims, asOf)).stream()
                .filter(claim -> claim.countsLive(holds.getOrDefault(claim.getPersonId(), List.of()),
                        retiredFrom.get(claim.getPersonId()), today))
                .collect(Collectors.groupingBy(Claim::getPersonId));
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = NONE;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : sheetPeople) {
            Figures f = figuresOf(sheetId, today, asOf, person, counted.getOrDefault(person.getId(), List.of()));
            figures.put(person.getId(), f);
            claimedTotal = claimedTotal.add(f.claimed());
            if (f.due() != null) {
                dues.add(f.due());
            }
            overdues.add(f.overdue());
        }
        return new Rollup(figures, claimedTotal, trimmedSum(dues), trimmedSum(overdues));
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are
     *  three or more. */
    private static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(NONE, BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        return sum.subtract(Collections.max(figures)).subtract(Collections.min(figures));
    }

    /** A person's figures from their claims that count: the sum of their amounts, leaving out those
     *  that correct another claim, and the sum of the
     *  figures they show, or none when the grade in force on the sheet's today has no rate in force
     *  then and no claim of theirs shows a figure; the sum is held to the sheet's cap. The overdue-total
     *  sums the dues of those that are OPEN or PENDING with a due-by plus their grace before the sheet's today. */
    private Figures figuresOf(String sheetId, LocalDate today, LocalDate asOf, Person person, List<Claim> counted) {
        BigDecimal claimed = NONE;
        BigDecimal due = NONE;
        BigDecimal overdue = NONE;
        boolean anyFigure = false;
        for (Claim claim : counted) {
            if (claim.getCorrects() == null) {
                claimed = claimed.add(claim.getAmount());
            }
            BigDecimal figure = claimService.dueOf(sheetId, person, claim, asOf);
            if (figure != null) {
                due = due.add(figure);
                if (claim.getStatus().isOpen() && claimService.overdueFrom(sheetId, person, claim, asOf).isBefore(today)) {
                    overdue = overdue.add(figure);
                }
            }
            anyFigure |= figure != null;
        }
        boolean hasRate = rates.rateOn(sheetId, grades.gradeOn(person.getId(), today, asOf), today, asOf).isPresent();
        return new Figures(person.getName(), claimed, hasRate || anyFigure ? settings.capped(sheetId, due, asOf) : null,
                overdue);
    }

    private static String text(BigDecimal due) {
        return due == null ? NO_RATE : due.toPlainString();
    }
}
