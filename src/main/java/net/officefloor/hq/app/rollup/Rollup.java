package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rate.RateChains;
import net.officefloor.hq.app.sheet.Preview;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * The figures of a sheet as the screen shows them: each person's claimed, due-total and overdue-total, and the
 * sheet's claimed-total, due-total and overdue-total (the latter two trimmed of the single highest and lowest
 * person's figure when three or more people have one). A change takes the figures before it and, once made, records
 * a ROLLUP line for each person whose figures moved and a TOTALS line when any of the sheet's totals moved.
 */
@Service
public class Rollup {

    /** A person's figures: claimed, due-total and overdue-total, each with two decimals; due-total is "n/a" when they
     *  have none; and, as the screen shows them, their settled-total and open-total (their due-total before the cap
     *  split by status: the dues of their SETTLED claims that count, and of their OPEN and PENDING ones, with the parts
     *  they hold of others' claims, none counting as 0) and their cut total, each with two decimals. */
    public record PersonFigures(String name, String claimed, String dueTotal, String overdueTotal,
            String settledTotal, String openTotal, String cutTotal) {
    }

    /** A part a person holds of another person's claim. */
    private record HeldPart(Claim claim, BigDecimal part) {
    }

    /** A sheet's figures: each person's, by id in the order they were added, and the sheet's totals. */
    public record Figures(Map<Long, PersonFigures> people, String claimedTotal, String dueTotal, String overdueTotal) {
    }

    private static final String NOT_AVAILABLE = "n/a";

    private final SheetRepository sheets;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateChains rates;
    private final PeriodRepository periods;
    private final Audit audit;
    private final PoolService pools;

    public Rollup(SheetRepository sheets, PersonRepository people, ClaimRepository claims, RateChains rates,
            PeriodRepository periods, Audit audit, @Lazy PoolService pools) {
        this.pools = pools;
        this.periods = periods;
        this.sheets = sheets;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.audit = audit;
    }

    /** The sheet's figures as the store holds them now. */
    public Figures of(String sheetId) {
        return of(sheets.findById(sheetId).orElse(null), rates.of(sheetId),
                people.findBySheetIdOrderByPositionAscIdAsc(sheetId), claims.findBySheetIdOrderByListedDescIdDesc(sheetId));
    }

    /** The figures of a sheet holding these rates, people (in list order) and claims (in list order), worked out on
     *  its today: the live sheet's, with each person's cut total as it stands now (none: no sheet). */
    public Figures of(Sheet sheet, RateChain sheetRates, List<Person> sheetPeople, List<Claim> sheetClaims) {
        return of(sheet, sheetRates, sheetPeople, sheetClaims,
                sheet == null ? Map.of() : pools.cutTotals(sheet.getId(), null));
    }

    /** The sheet's figures as the store holds them now, worked out as a screen previewing a date reads them (null:
     *  none), that date standing in for its today. */
    public Figures previewed(String sheetId, LocalDate preview) {
        return previewed(sheets.findById(sheetId).orElse(null), rates.of(sheetId),
                people.findBySheetIdOrderByPositionAscIdAsc(sheetId), claims.findBySheetIdOrderByListedDescIdDesc(sheetId),
                preview);
    }

    /** The figures of the live sheet as {@link #of(Sheet, RateChain, List, List)}, worked out as a screen previewing a
     *  date reads them (null: none): over its current records, that date standing in for its today. */
    public Figures previewed(Sheet sheet, RateChain sheetRates, List<Person> sheetPeople, List<Claim> sheetClaims,
            LocalDate preview) {
        if (sheet == null || preview == null) {
            return of(sheet, sheetRates, sheetPeople, sheetClaims);
        }
        return of(Preview.of(sheet, preview), sheetRates, sheetPeople, sheetClaims,
                pools.cutTotals(sheet.getId(), null, preview));
    }

    /** The figures of a sheet as {@link #of(Sheet, RateChain, List, List)}, with these cut totals by person id (a
     *  person left out has none): the live sheet's, or one rebuilt as it stood at a moment (none: no sheet). */
    public Figures of(Sheet sheet, RateChain sheetRates, List<Person> sheetPeople, List<Claim> sheetClaims,
            Map<Long, BigDecimal> cutTotals) {
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, PersonFigures> byPerson = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        Map<Long, Person> ownerOf = new LinkedHashMap<>();
        sheetPeople.forEach(p -> ownerOf.put(p.getId(), p));
        for (Person person : sheetPeople) {
            List<Claim> counting = sheetClaims.stream()
                    .filter(c -> c.getPersonId().equals(person.getId()) && person.counts(c, today)
                            && !c.isDeferredOn(today))
                    .toList();
            // Claimed leaves out corrections: claims that correct another; a held claim still counts in it.
            BigDecimal claimed = sheetClaims.stream()
                    .filter(c -> c.getPersonId().equals(person.getId()) && person.countsClaimed(c, today)
                            && !c.isDeferredOn(today) && c.getCorrects() == null)
                    .map(Claim::getAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            claimedTotal = claimedTotal.add(claimed);
            // As on screen: with no rate in force today for their grade on the sheet's today, nor for the grade
            // any of their claims is at, and no claim that has a due, the person has no due-total.
            // A shared claim's own person counts its due less its holders' parts; each holder counts their part.
            List<HeldPart> ownParts = Stream.concat(counting.stream()
                    .map(c -> {
                        BigDecimal claimDue = dueOf(c, person.gradeOn(c.getDate()), sheetRates, sheet, sheetClaims);
                        return claimDue == null ? null : new HeldPart(c,
                                claimDue.subtract(sharedPartOf(c, claimDue, ownerOf::get, sheetRates, sheet, sheetClaims)));
                    }), heldPartsOf(person, ownerOf, today, sheetRates, sheet, sheetClaims).stream())
                    .filter(Objects::nonNull)
                    .toList();
            List<BigDecimal> dues = ownParts.stream().map(HeldPart::part).toList();
            BigDecimal settled = ownParts.stream().filter(h -> h.claim().getStatus() == ClaimStatus.SETTLED)
                    .map(HeldPart::part).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            BigDecimal open = ownParts.stream().filter(h -> h.claim().getStatus() == ClaimStatus.OPEN
                            || h.claim().getStatus() == ClaimStatus.PENDING)
                    .map(HeldPart::part).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            BigDecimal cut = cutTotals.getOrDefault(person.getId(), BigDecimal.ZERO).setScale(2);
            boolean anyRate = today != null && Stream.concat(Stream.of(person.gradeOn(today)),
                            counting.stream().map(c -> person.gradeOn(c.getDate())))
                    .anyMatch(grade -> sheetRates.inForceOn(grade, today).isPresent());
            String due = NOT_AVAILABLE;
            if (!dues.isEmpty() || anyRate) {
                BigDecimal sum = dues.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                BigDecimal total = sheet == null ? sum : sheet.capped(sum,
                        cutTotals.getOrDefault(person.getId(), BigDecimal.ZERO.setScale(2)),
                        counting.stream().map(Claim::getDate).max(Comparator.naturalOrder()).orElse(null));
                dueFigures.add(total);
                due = total.toPlainString();
            }
            // As on screen: the dues of the claims that count which are open and due by (plus their grade's grace
            // days) a day before the sheet's today.
            BigDecimal overdue = counting.stream()
                    .filter(c -> today != null && ClaimService.isOverdue(c, sheet, person.gradeOn(c.getDate())))
                    .map(c -> dueOf(c, person.gradeOn(c.getDate()), sheetRates, sheet, sheetClaims))
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            overdueFigures.add(overdue);
            byPerson.put(person.getId(),
                    new PersonFigures(person.getName(), claimed.toPlainString(), due, overdue.toPlainString(),
                            settled.toPlainString(), open.toPlainString(), cut.toPlainString()));
        }
        return new Figures(byPerson, claimedTotal.toPlainString(), trimmedSum(dueFigures).toPlainString(),
                trimmedSum(overdueFigures).toPlainString());
    }

    /** The parts a person holds of the live sheet's other people's claims that count on its today, as
     *  {@link #heldParts}: what their due-total adds for the claims shared with them. */
    public List<BigDecimal> heldParts(Sheet sheet, RateChain sheetRates, Person person) {
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheet.getId());
        Map<Long, Person> ownerOf = new LinkedHashMap<>();
        sheetPeople.forEach(p -> ownerOf.put(p.getId(), p));
        return heldParts(person, ownerOf, sheet.getToday(), sheetRates, sheet,
                claims.findBySheetIdOrderByListedDescIdDesc(sheet.getId()));
    }

    /** The parts a person holds of other people's claims that count (as for their own person), each as
     *  {@link #partOf}; a claim with no due adds none, nor does one whose part the person's grade has no rate for. */
    private static List<BigDecimal> heldParts(Person person, Map<Long, Person> ownerOf, LocalDate today,
            RateChain sheetRates, Sheet sheet, List<Claim> sheetClaims) {
        return heldPartsOf(person, ownerOf, today, sheetRates, sheet, sheetClaims).stream().map(HeldPart::part).toList();
    }

    /** The parts as {@link #heldParts}, each with the claim it is a part of. */
    private static List<HeldPart> heldPartsOf(Person person, Map<Long, Person> ownerOf, LocalDate today,
            RateChain sheetRates, Sheet sheet, List<Claim> sheetClaims) {
        List<HeldPart> parts = new ArrayList<>();
        for (Claim claim : sheetClaims) {
            ClaimShare share = claim.shareOf(person.getId());
            Person owner = ownerOf.get(claim.getPersonId());
            if (share == null || owner == null || !owner.counts(claim, today) || claim.isDeferredOn(today)) {
                continue;
            }
            BigDecimal due = dueOf(claim, owner.gradeOn(claim.getDate()), sheetRates, sheet, sheetClaims);
            BigDecimal part = due == null ? null : partOf(claim, share, due, person, sheetRates, sheet, sheetClaims);
            if (part != null) {
                parts.add(new HeldPart(claim, part));
            }
        }
        return parts;
    }

    /** The parts of a claim's due its holders hold, added up, each as {@link #partOf} (one with no rate adding
     *  0.00); the holders are found by person id. */
    public static BigDecimal sharedPartOf(Claim claim, BigDecimal due, Function<Long, Person> holders,
            RateChain sheetRates, Sheet sheet, Collection<Claim> roundingClaims) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (ClaimShare share : claim.getShares()) {
            Person holder = holders.apply(share.getPersonId());
            BigDecimal part = holder == null ? null
                    : partOf(claim, share, due, holder, sheetRates, sheet, roundingClaims);
            total = part == null ? total : total.add(part);
        }
        return total;
    }

    /** A holder's part of a claim with this due: a settled claim keeps its parts frozen, its settled due times the
     *  holder's share divided by 100, rounded half to even; any other claim's is its amount times the holder's share
     *  divided by 100, times the rate of the holder's own grade in force on the claim's date divided by 100, rounded
     *  as the claim rounds; null when their grade has no rate then. */
    public static BigDecimal partOf(Claim claim, ClaimShare share, BigDecimal due, Person holder,
            RateChain sheetRates, Sheet sheet, Collection<Claim> roundingClaims) {
        if (claim.getSettledDue() != null) {
            return share.partOf(due);
        }
        RoundingMode rounding = sheet == null ? RoundingMode.HALF_EVEN
                : sheet.roundingFor(ClaimService.roundingDate(claim, roundingClaims));
        return sheetRates.inForceOn(holder.gradeOn(claim.getDate()), claim.getDate(), claim.getId())
                .map(rate -> share.partOf(claim.getAmount(), rate.getRate(), rounding))
                .orElse(null);
    }

    /** A person's score, as the screen shows it. */
    public record PersonScore(String name, long score) {
    }

    /** Each person's score on the sheet's today as the store holds it now, by id in list order, as the screen shows
     *  it: the dues of their SETTLED claims that count and correct nothing, less 500.00 for each of their overdue claims that count,
     *  divided by 100 and rounded half to even to a whole number. */
    public Map<Long, PersonScore> scoresOf(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        RateChain sheetRates = rates.of(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<Long, PersonScore> byPerson = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            List<Claim> counting = sheetClaims.stream()
                    .filter(c -> c.getPersonId().equals(person.getId()) && person.counts(c, today)
                            && !c.isDeferredOn(today))
                    .toList();
            BigDecimal settled = counting.stream()
                    .filter(c -> c.getStatus() == ClaimStatus.SETTLED && c.getCorrects() == null)
                    .map(c -> dueOf(c, person.gradeOn(c.getDate()), sheetRates, sheet, sheetClaims))
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            long overdue = counting.stream()
                    .filter(c -> today != null && ClaimService.isOverdue(c, sheet, person.gradeOn(c.getDate())))
                    .count();
            BigDecimal score = settled.subtract(BigDecimal.valueOf(500 * overdue))
                    .divide(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_EVEN);
            byPerson.put(person.getId(), new PersonScore(person.getName(), score.longValueExact()));
        }
        return byPerson;
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    public static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        BigDecimal highest = figures.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal lowest = figures.stream().min(BigDecimal::compareTo).orElseThrow();
        return sum.subtract(highest).subtract(lowest);
    }

    /** Each person's due-total for the dates from {@code from} to {@code to} inclusive, by person id, as the screen
     *  shows a person's period figure: the dues as shown now of their claims that counted by the status they had at the end
     *  of {@code to} (a held person's open ones dated within a
     *  hold and not stated do not) due by in it, none counting as 0; every person on the sheet has one. */
    public Map<Long, BigDecimal> dueByPersonBetween(String sheetId, LocalDate from, LocalDate to) {
        RateChain sheetRates = rates.of(sheetId);
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, Person> personOf = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> {
            personOf.put(p.getId(), p);
            totals.put(p.getId(), BigDecimal.ZERO.setScale(2));
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || !person.countsAtEnd(claim, to, today) || claim.isDeferredOn(today) || claim.getDueBy().isBefore(from) || claim.getDueBy().isAfter(to)) {
                continue;
            }
            BigDecimal due = dueOf(claim, person.gradeOn(claim.getDate()), sheetRates, sheet, sheetClaims);
            if (due != null) {
                totals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        return totals;
    }

    /** Each person's carried figure for a period, by person id, as the screen shows it: the dues as shown now of
     *  their claims that count (as for the period's figures) which are due by in a period of the sheet ending before
     *  this one starts and were still OPEN or PENDING at the end of this period's last day, none counting as 0, plus
     *  their opening balance for it; every person on the sheet has one. */
    public Map<Long, BigDecimal> carriedByPerson(String sheetId, Period period) {
        RateChain sheetRates = rates.of(sheetId);
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        LocalDate end = period.getToDate();
        List<Period> sheetPeriods = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        Map<Long, Person> personOf = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> {
            personOf.put(p.getId(), p);
            totals.put(p.getId(), period.openingOf(p.getId()));
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || !person.countsAtEnd(claim, end, today) || claim.isDeferredOn(today)) {
                continue;
            }
            ClaimStatus status = claim.statusAt(end);
            if (status != ClaimStatus.OPEN && status != ClaimStatus.PENDING) {
                continue;
            }
            boolean earlier = sheetPeriods.stream().filter(p -> p.contains(claim.getDueBy())).findFirst()
                    .map(own -> own.getToDate().isBefore(period.getFromDate())).orElse(false);
            if (!earlier) {
                continue;
            }
            BigDecimal due = dueOf(claim, person.gradeOn(claim.getDate()), sheetRates, sheet, sheetClaims);
            if (due != null) {
                totals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        return totals;
    }

    /** Each person's open figure at {@code end}, by person id: the dues as shown now of their claims that count (as for
     *  the period's figures) which are due by in one of {@code yearPeriods} and were still OPEN or PENDING at the end
     *  of {@code end}, none counting as 0; every person on the sheet has one. */
    public Map<Long, BigDecimal> openByPersonAt(String sheetId, List<Period> yearPeriods, LocalDate end) {
        RateChain sheetRates = rates.of(sheetId);
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, Person> personOf = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> {
            personOf.put(p.getId(), p);
            totals.put(p.getId(), BigDecimal.ZERO.setScale(2));
        });
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || !person.countsAtEnd(claim, end, today) || claim.isDeferredOn(today)) {
                continue;
            }
            ClaimStatus status = claim.statusAt(end);
            if (status != ClaimStatus.OPEN && status != ClaimStatus.PENDING) {
                continue;
            }
            if (yearPeriods.stream().noneMatch(p -> p.contains(claim.getDueBy()))) {
                continue;
            }
            BigDecimal due = dueOf(claim, person.gradeOn(claim.getDate()), sheetRates, sheet, sheetClaims);
            if (due != null) {
                totals.merge(person.getId(), due, BigDecimal::add);
            }
        }
        return totals;
    }

    /** What a candidate rate would change: how many claims would show a different due, and the sum of the
     *  differences (new due minus current due), with two decimals. */
    public record RatePreview(int count, String diff) {
    }

    /** How the OPEN and PENDING claims of people on a grade (in force on each claim's date) would change were a rate
     *  to apply to that grade on every date: a claim bound by a statement does not change and a settled one is not
     *  counted; a due of none counts as 0 in the sum. Nothing is stored. */
    public RatePreview preview(String sheetId, String grade, BigDecimal rate) {
        RateChain sheetRates = rates.of(sheetId);
        RateChain candidate = sheetRates.withOwn(Stream.concat(
                sheetRates.levels().get(0).stream().filter(r -> !r.getGrade().equals(grade)),
                Stream.of(new Rate(sheetId, grade, null, rate.setScale(2, RoundingMode.UNNECESSARY)))).toList());
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        Map<Long, Person> personOf = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(p -> personOf.put(p.getId(), p));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : sheetClaims) {
            Person person = personOf.get(claim.getPersonId());
            if (person == null || claim.getSettledDue() != null
                    || (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING)
                    || !grade.equals(person.gradeOn(claim.getDate()))) {
                continue;
            }
            BigDecimal now = dueOf(claim, grade, sheetRates, sheet, sheetClaims);
            BigDecimal then = dueOf(claim, grade, candidate, sheet, sheetClaims);
            if (!Objects.equals(now, then)) {
                count++;
                diff = diff.add(then == null ? BigDecimal.ZERO : then)
                        .subtract(now == null ? BigDecimal.ZERO : now);
            }
        }
        return new RatePreview(count, diff.setScale(2).toPlainString());
    }

    /**
     * Record, once the change commits, how the sheet's figures moved since {@code before}: a ROLLUP line
     * for each person whose figures changed (one who did not exist before counting as changed), then a
     * TOTALS line if the sheet's totals changed, then the ALERT lines of the people who crossed the sheet's alert
     * threshold (see {@link #recordAlerts}). Nothing is recorded when no figure moved.
     */
    public void recordChanges(String sheetId, String user, Figures before) {
        Figures after = of(sheetId);
        after.people().forEach((id, figures) -> {
            PersonFigures was = before.people().get(id);
            if (was == null || !was.claimed().equals(figures.claimed()) || !was.dueTotal().equals(figures.dueTotal())
                    || !was.overdueTotal().equals(figures.overdueTotal())) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                        + " claimed=" + figures.claimed() + " due-total=" + figures.dueTotal()
                        + " overdue-total=" + figures.overdueTotal());
            }
        });
        if (!before.claimedTotal().equals(after.claimedTotal()) || !before.dueTotal().equals(after.dueTotal())
                || !before.overdueTotal().equals(after.overdueTotal())) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal() + " overdue-total=" + after.overdueTotal());
        }
        recordAlerts(sheetId, user, before, after);
    }

    /**
     * Record, once the change commits, an ALERT line, in list order, for each person whose due-total went from at or
     * below the sheet's alert threshold (or none, or not being on the sheet) to above it between {@code before} (null:
     * nobody was) and {@code after}. Nothing is recorded while the threshold is off.
     */
    public void recordAlerts(String sheetId, String user, Figures before, Figures after) {
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold == null) {
            return;
        }
        after.people().forEach((id, figures) -> {
            PersonFigures was = before == null ? null : before.people().get(id);
            if (above(figures, threshold) && (was == null || !above(was, threshold))) {
                audit.recordOnCommit("ALERT sheet=" + sheetId + " by=" + user + " name=" + figures.name()
                        + " due-total=" + figures.dueTotal());
            }
        });
    }

    private static boolean above(PersonFigures figures, BigDecimal threshold) {
        return !NOT_AVAILABLE.equals(figures.dueTotal()) && new BigDecimal(figures.dueTotal()).compareTo(threshold) > 0;
    }

    /** A claim's due: its settled figure, else its stated one, else its amount at the rate in force on its date of
     *  its person's grade in force on that date (the sheet's own, else its nearest ancestor's), rounded as the claim rounds (by the sheet's policy date) and raised to the floor in force on its date unless it is a
     *  correction; an overdue claim's late charge, worked out on that figure, is added on top of it. */
    private static BigDecimal dueOf(Claim claim, String grade, RateChain sheetRates, Sheet sheet, List<Claim> sheetClaims) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        RoundingMode rounding = sheet == null ? RoundingMode.HALF_EVEN
                : sheet.roundingFor(ClaimService.roundingDate(claim, sheetClaims));
        BigDecimal figure = claim.isBound() ? claim.getBoundDue()
                : sheetRates.inForceOn(grade, claim.getDate(), claim.getId())
                        .map(rate -> ClaimService.floored(claim, ClaimService.liveFigure(claim, rate.getRate(), rounding), sheet))
                        .orElse(null);
        return figure == null || sheet == null ? figure
                : figure.add(ClaimService.lateCharge(claim, figure, sheet, grade));
    }
}
