package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonGradeService;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.pool.PoolCutService;
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
    private record Figures(String name, BigDecimal claimed, BigDecimal due, BigDecimal overdue, BigDecimal settled,
            int overdueCount) {
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
    private final PoolCutService cuts;
    private final Audit audit;

    public RollupService(ClaimRepository claims, ClaimService claimService,
            RateService rates, PersonGradeService grades, PersonService personService, SheetService sheets,
            SettingService settings, History history, PoolCutService cuts, Audit audit) {
        this.history = history;
        this.cuts = cuts;
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

    /** A sheet's headline figures as it shows them now: how many people are on it, its due-total
     *  and overdue-total, and how many of its claims that count are overdue. */
    public record Headline(int people, BigDecimal dueTotal, BigDecimal overdueTotal, int overdueCount) {
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
        return new Headline(rollup.people().size(), rollup.dueTotal(), rollup.overdueTotal(),
                rollup.people().values().stream().mapToInt(Figures::overdueCount).sum());
    }

    /** A person's figures on a sheet as it shows them: their name and aliases, their due-total (null
     *  when they have none) and their overdue-total. */
    public record PersonFigures(String name, List<String> aliases, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** Each person's figures on the sheet as it stood at the end of a day, that day standing in for
     *  the sheet's today (null: as it shows them now), in list order. */
    @Transactional(readOnly = true)
    public List<PersonFigures> personFigures(String sheetId, LocalDate asOf) {
        Rollup rollup = rollup(sheetId, asOf);
        Map<Long, List<String>> aliases = personService.aliases(sheetId, asOf);
        List<PersonFigures> list = new ArrayList<>();
        rollup.people().forEach((personId, f) -> list.add(new PersonFigures(f.name(),
                aliases.getOrDefault(personId, List.of()), f.due(), f.overdue())));
        return list;
    }

    /** A sheet's figures broken down: per grade, the due-totals of the people on it who are not
     *  retired added up (skipping those with no figure); per status, how many of its claims have it. */
    public record Breakdown(Map<String, BigDecimal> gradeDueTotals, Map<String, Integer> statusCounts) {
    }

    /** The sheet's figures broken down as it stood at the end of a day, that day standing in for the
     *  sheet's today (null: as it shows them now). */
    @Transactional(readOnly = true)
    public Breakdown breakdown(String sheetId, LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        Rollup rollup = rollup(sheetId, asOf);
        Map<String, BigDecimal> gradeDues = new LinkedHashMap<>();
        for (Person person : personService.list(sheetId, asOf)) {
            Figures f = rollup.people().get(person.getId());
            String grade = grades.gradeOn(person.getId(), today, asOf);
            if (person.isRetired() || f == null || grade == null) {
                continue;
            }
            gradeDues.merge(grade, f.due() == null ? NONE : f.due(), BigDecimal::add);
        }
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDesc(sheetId);
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        (asOf == null ? sheetClaims : history.claims(sheetClaims, asOf))
                .forEach(claim -> statusCounts.merge(claim.getStatus().name(), 1, Integer::sum));
        return new Breakdown(gradeDues, statusCounts);
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
     *  day the sheet's today is moving to. While the score log is on, each person's score then is
     *  recorded first, in list order. */
    public void snapshot(String sheetId, String user, LocalDate movingTo) {
        LocalDate leaving = sheets.today(sheetId);
        Rollup rollup = rollup(sheetId);
        if (settings.scoreLog(sheetId)) {
            rollup.people().values().forEach(figures -> audit.recordOnCommit("SCORE sheet=" + sheetId + " by=" + user
                    + " name=" + figures.name() + " score=" + score(figures).toPlainString()));
        }
        rollup.people().forEach((personId, figures) -> {
            personService.snapshot(sheetId, personId, leaving, text(figures.due()), movingTo);
            audit.recordOnCommit("SNAPSHOT sheet=" + sheetId + " by=" + user + " name=" + figures.name()
                    + " due-total=" + text(figures.due()));
        });
    }

    /** A person's score: their settled figures (corrections left out) divided by 100, minus 5 for each overdue claim of theirs
     *  that counts, rounded half to even to a whole number. */
    private static BigDecimal score(Figures figures) {
        BigDecimal score = figures.settled().movePointLeft(2).subtract(BigDecimal.valueOf(5L * figures.overdueCount()))
                .setScale(0, RoundingMode.HALF_EVEN);
        return score.signum() == 0 ? BigDecimal.ZERO : score;
    }

    private Rollup rollup(String sheetId) {
        return rollup(sheetId, null);
    }

    /** The sheet's figures as it stood at the end of a day: its people, claims, holds, grades, rates
     *  and settings as they stood then, the day standing in for the sheet's today (null: now). */
    private Rollup rollup(String sheetId, LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        List<Person> sheetPeople = personService.list(sheetId, asOf);
        Map<Long, LocalDate> retiredFrom = new HashMap<>();
        sheetPeople.forEach(person -> retiredFrom.put(person.getId(), person.getRetiredFrom()));
        Map<Long, List<Claim>> counted = counted(sheetId, asOf, today, retiredFrom);
        Map<Long, BigDecimal> shared = sharedParts(sheetId, asOf, sheetPeople, counted);
        Map<Long, BigDecimal> cutTotals = cuts.cutTotals(sheetId, asOf, sheetPeople);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = NONE;
        List<BigDecimal> dues = new ArrayList<>();
        List<BigDecimal> overdues = new ArrayList<>();
        for (Person person : sheetPeople) {
            Figures f = figuresOf(sheetId, today, asOf, person, counted.getOrDefault(person.getId(), List.of()),
                    shared.get(person.getId()), cutTotals.getOrDefault(person.getId(), NONE));
            figures.put(person.getId(), f);
            claimedTotal = claimedTotal.add(f.claimed());
            if (f.due() != null) {
                dues.add(f.due());
            }
            overdues.add(f.overdue());
        }
        return new Rollup(figures, claimedTotal, trimmedSum(dues), trimmedSum(overdues));
    }

    /** The sheet's claims that count, as they stood at the end of a day (null: now), by person id. */
    private Map<Long, List<Claim>> counted(String sheetId, LocalDate asOf, LocalDate today,
            Map<Long, LocalDate> retiredFrom) {
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDesc(sheetId);
        return (asOf == null ? sheetClaims : history.claims(sheetClaims, asOf)).stream()
                .filter(claim -> claim.countsLive(retiredFrom.get(claim.getPersonId()), today))
                .collect(Collectors.groupingBy(Claim::getPersonId));
    }

    /** What the shares of the claims that count move in a person's due-total, as it stood at the end of
     *  a day (null: now); null when no share moves it. */
    public BigDecimal sharedPartOf(String sheetId, Long personId, LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : sheets.today(sheetId);
        List<Person> sheetPeople = personService.list(sheetId, asOf);
        Map<Long, LocalDate> retiredFrom = new HashMap<>();
        sheetPeople.forEach(person -> retiredFrom.put(person.getId(), person.getRetiredFrom()));
        return sharedParts(sheetId, asOf, sheetPeople, counted(sheetId, asOf, today, retiredFrom)).get(personId);
    }

    /** What the shares of the claims that count move in each person's due-total, by person id: each
     *  holder gains their part of a claim's figure (its due times their share divided by 100, rounded
     *  half to even), and the claim's own person loses it. A person whose figures no share moves is
     *  not listed; a claim with no figure moves nothing. */
    private Map<Long, BigDecimal> sharedParts(String sheetId, LocalDate asOf, List<Person> sheetPeople,
            Map<Long, List<Claim>> counted) {
        Map<Long, BigDecimal> parts = new HashMap<>();
        Set<Long> onSheet = sheetPeople.stream().map(Person::getId).collect(Collectors.toSet());
        for (Person person : sheetPeople) {
            for (Claim claim : counted.getOrDefault(person.getId(), List.of())) {
                if (claim.getShares().isEmpty()) {
                    continue;
                }
                BigDecimal figure = claimService.dueOf(sheetId, person, claim, asOf);
                if (figure == null) {
                    continue;
                }
                for (ClaimShare share : claim.getShares()) {
                    if (!onSheet.contains(share.personId())) {
                        continue;
                    }
                    BigDecimal part = share.partOf(figure);
                    parts.merge(share.personId(), part, BigDecimal::add);
                    parts.merge(person.getId(), part.negate(), BigDecimal::add);
                }
            }
        }
        return parts;
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
     *  figures they show (a held one's included), or none when the grade in force on the sheet's today has no rate in force
     *  then and no claim of theirs shows a figure; the sum is held to the sheet's cap less their cut total. The overdue-total
     *  sums the dues of those that are OPEN or PENDING with a due-by plus their grace before the sheet's today.
     *  The settled figures, for the score, sum those of SETTLED claims that correct nothing. The
     *  due-total takes in what the shares of claims move (null: none moves it): a holder's parts, less
     *  the parts others hold of the person's own claims. */
    private Figures figuresOf(String sheetId, LocalDate today, LocalDate asOf, Person person, List<Claim> counted,
            BigDecimal sharedPart, BigDecimal cutTotal) {
        BigDecimal claimed = NONE;
        BigDecimal due = NONE;
        BigDecimal overdue = NONE;
        BigDecimal settled = NONE;
        int overdueCount = 0;
        boolean anyFigure = false;
        for (Claim claim : counted) {
            if (claim.getCorrects() == null) {
                claimed = claimed.add(claim.getAmount());
            }
            BigDecimal figure = claimService.dueOf(sheetId, person, claim, asOf);
            boolean late = claim.getStatus().isOpen()
                    && claimService.overdueFrom(sheetId, person, claim, asOf).isBefore(today);
            if (late) {
                overdueCount++;
            }
            if (figure != null && claim.getStatus() == ClaimStatus.SETTLED && claim.getCorrects() == null) {
                settled = settled.add(figure);
            }
            if (figure != null) {
                due = due.add(figure);
                if (late) {
                    overdue = overdue.add(figure);
                }
            }
            anyFigure |= figure != null;
        }
        if (sharedPart != null) {
            due = due.add(sharedPart);
            anyFigure = true;
        }
        boolean hasRate = rates.rateOn(sheetId, grades.gradeOn(person.getId(), today, asOf), today, asOf).isPresent();
        return new Figures(person.getName(), claimed, hasRate || anyFigure ? settings.capped(sheetId, due, cutTotal, asOf) : null,
                overdue, settled, overdueCount);
    }

    private static String text(BigDecimal due) {
        return due == null ? NO_RATE : due.toPlainString();
    }
}
