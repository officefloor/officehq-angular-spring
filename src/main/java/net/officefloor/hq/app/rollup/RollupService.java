package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.claim.ClaimShareRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.settings.DatedSettings;
import net.officefloor.hq.app.sheet.PreviewDate;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures each person on a sheet shows — what they have claimed (their OPEN, PENDING and SETTLED
 * claims that are not corrections, leaving out the OPEN ones deferred on the sheet's today, and the OPEN and PENDING
 * ones once their retirement date is reached on the sheet's today that no statement binds; a hold leaves them in)
 * and their due-total (the sum of what is due on the claims that count live as each shows it, n/a when none has a figure and no rate of
 * their grade is in force on the sheet's today, and at most the sheet's cap in force on its today less their cut total
 * (their cuts across every pool, see {@link PoolService}), not below 0.00, when it has one; of a
 * shared claim each holder counts their part (see {@link ClaimShare#partOf}) and its own person the rest) and their
 * overdue-total (the sum of what is due on those that are OPEN or PENDING and past their due-by on the
 * sheet's today, as each shows it) — and the sheet's totals of them (the due-total and overdue-total
 * leave out the single highest and lowest person's figure when three or more people have one; a
 * due-total of n/a is no figure). A change that moves
 * any of them is followed in the audit by a ROLLUP line per person whose figures moved, then a TOTALS
 * line when the sheet's totals moved, then an ALERT line per person, in list order, whose due-total went
 * from at or below the sheet's alert threshold (or none) to above it: take a {@link #snapshot} before the change, and
 * {@link #recordSince} it after the change has recorded its own lines.
 */
@Service
public class RollupService {

    /** A due-total shown when there is none. */
    public static final String NONE = "n/a";

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimShareRepository shares;
    private final RateChain rates;
    private final SheetRepository sheets;
    private final DatedSettings dated;
    private final Audit audit;
    private final PoolService pools;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimShareRepository shares,
            RateChain rates, SheetRepository sheets, DatedSettings dated, Audit audit, PoolService pools) {
        this.pools = pools;
        this.shares = shares;
        this.dated = dated;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** A person's figures, as shown. */
    public record Figures(String name, String claimed, String dueTotal, String overdueTotal) {
    }

    /** The sheet's figures at one moment: each person's, in list order, and the totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal, String overdueTotal) {
    }

    /** The figures the sheet shows now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot snapshot(String sheetId) {
        return snapshot(people.findBySheetIdOrderByPositionAscIdAsc(sheetId),
                claims.findBySheetIdOrderByListedDescIdDesc(sheetId), live(sheetId));
    }

    /** The figures a sheet shows on {@code basis}, from {@code everyone} on it, in list order, and their
     * claims {@code all}: as {@link #snapshot(String)} works them out for the sheet as it stands. */
    public Snapshot snapshot(List<Person> everyone, List<Claim> all, Basis basis) {
        LocalDate today = basis.today();
        BigDecimal cap = basis.cap();
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        Map<Long, List<ClaimShare>> sharesByClaim = new LinkedHashMap<>();
        for (ClaimShare share : basis.shares()) {
            sharesByClaim.computeIfAbsent(share.getClaimId(), k -> new ArrayList<>()).add(share);
        }
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (Person person : everyone) byId.put(person.getId(), person);
        Map<Long, BigDecimal> heldParts = new LinkedHashMap<>();
        for (Claim claim : all) {
            List<ClaimShare> held = sharesByClaim.get(claim.getId());
            Person owner = byId.get(claim.getPersonId());
            if (held == null || owner == null || !countsLive(claim, owner, today)) continue;
            BigDecimal figure = shownOf(claim, owner, basis);
            if (figure == null) continue;
            for (ClaimShare share : held) heldParts.merge(share.getPersonId(), share.partOf(figure), BigDecimal::add);
        }
        for (Person person : everyone) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean any = false;
            for (Claim claim : all) {
                if (!claim.getPersonId().equals(person.getId())) continue;
                if (countsClaimed(claim, person, today) && !claim.isCorrection()) claimed = claimed.add(claim.getAmount());
                if (!countsLive(claim, person, today)) continue;
                BigDecimal figure = shownOf(claim, person, basis);
                if (figure == null) continue;
                if (isOverdue(claim, basis)) overdue = overdue.add(figure);
                due = due.add(ownPartOf(figure, sharesByClaim.get(claim.getId())));
                any = true;
            }
            BigDecimal part = heldParts.get(person.getId());
            if (part != null) {
                due = due.add(part);
                any = true;
            }
            due = cappedOf(due, cap, basis.cutOf(person.getId()));
            boolean rated = today != null && basis.rateInForce(person.gradeOn(today), today).isPresent();
            claimedTotal = claimedTotal.add(claimed);
            overdueFigures.add(overdue);
            if (any || rated) dueFigures.add(due);
            figures.put(person.getId(), new Figures(person.getName(), text(claimed), any || rated ? text(due) : NONE,
                    text(overdue)));
        }
        return new Snapshot(figures, text(claimedTotal), text(trimmedSum(dueFigures)), text(trimmedSum(overdueFigures)));
    }

    /** What is left of {@code figure} to the claim's own person once the holders of its {@code shares}
     * (none when null) have their parts (see {@link ClaimShare#partOf}). */
    static BigDecimal ownPartOf(BigDecimal figure, List<ClaimShare> shares) {
        if (shares == null) return figure;
        BigDecimal own = figure;
        for (ClaimShare share : shares) own = own.subtract(share.partOf(figure));
        return own;
    }

    /** Each person's score on the sheet as it stands, by person, in list order: their settled-total (what is due
     * on their counting SETTLED claims that are not corrections, as each shows it, before the cap) divided by 100, minus 5 for every
     * counting claim of theirs that is overdue, rounded half to even to a whole number. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Score> scores(String sheetId) {
        Basis basis = live(sheetId);
        LocalDate today = basis.today();
        List<Claim> all = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Map<Long, Score> scores = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            BigDecimal settled = zero();
            int overdue = 0;
            for (Claim claim : all) {
                if (!claim.getPersonId().equals(person.getId()) || !countsLive(claim, person, today)) continue;
                if (isOverdue(claim, basis)) overdue++;
                if (claim.getStatus().isOpen() || claim.isCorrection()) continue;
                BigDecimal figure = shownOf(claim, person, basis);
                if (figure != null) settled = settled.add(figure);
            }
            BigDecimal score = settled.movePointLeft(2).subtract(BigDecimal.valueOf(5L * overdue))
                    .setScale(0, RoundingMode.HALF_EVEN);
            scores.put(person.getId(), new Score(person.getName(), score.toPlainString()));
        }
        return scores;
    }

    /** A person's score, as shown. */
    public record Score(String name, String score) {
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(zero(), BigDecimal::add);
        if (figures.size() < 3) return sum;
        return sum.subtract(Collections.max(figures)).subtract(Collections.min(figures));
    }

    /** Record the ROLLUP and TOTALS lines for the figures that moved since {@code before}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSince(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        after.people().forEach((id, now) -> {
            Figures then = before.people().get(id);
            if (then != null && then.claimed().equals(now.claimed()) && then.dueTotal().equals(now.dueTotal())
                    && then.overdueTotal().equals(now.overdueTotal())) return;
            audit.change("ROLLUP", sheetId, user, "person=" + now.name(), "claimed=" + now.claimed(),
                    "due-total=" + now.dueTotal(), "overdue-total=" + now.overdueTotal());
        });
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())
                || !Objects.equals(before.overdueTotal(), after.overdueTotal())) {
            audit.change("TOTALS", sheetId, user, "claimed-total=" + after.claimedTotal(),
                    "due-total=" + after.dueTotal(), "overdue-total=" + after.overdueTotal());
        }
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold == null) return;
        after.people().forEach((id, now) -> {
            Figures then = before.people().get(id);
            if (above(now.dueTotal(), threshold) && (then == null || !above(then.dueTotal(), threshold))) {
                audit.change("ALERT", sheetId, user, "name=" + now.name(), "due-total=" + now.dueTotal());
            }
        });
    }

    /** Whether a due-total as shown is above the threshold (n/a never is). */
    private static boolean above(String dueTotal, BigDecimal threshold) {
        return !NONE.equals(dueTotal) && new BigDecimal(dueTotal).compareTo(threshold) > 0;
    }

    /** Whether the claim is overdue on {@code basis}: OPEN or PENDING, not deferred on its today, and
     * overdue from a date before it (its due-by plus any grace, see {@link Basis#overdueFrom}). */
    public static boolean isOverdue(Claim claim, Basis basis) {
        LocalDate today = basis.today();
        return claim.getStatus().isOpen() && today != null && claim.getDueBy() != null
                && !claim.isDeferredOn(today) && basis.overdueFrom(claim).isBefore(today);
    }

    /** A due-total as shown: at most {@code cap} less the person's cut total {@code cut}, not below 0.00, when
     * there is a cap. */
    public static BigDecimal cappedOf(BigDecimal due, BigDecimal cap, BigDecimal cut) {
        return cap == null ? due : due.min(cap.subtract(cut).max(zero()));
    }

    /** Whether the claim counts in the live totals on the sheet's {@code today}: OPEN, PENDING or
     * SETTLED, but not a claim deferred on {@code today} (see {@link Claim#isDeferredOn}), nor OPEN or PENDING when its person's
     * retirement date is reached on {@code today} and no statement binds it. A hold, the person's or the
     * claim's own, does not take it out. */
    public static boolean countsLive(Claim claim, Person person, LocalDate today) {
        return countsAs(claim, claim.getStatus(), person, today);
    }

    /** Whether the claim counts in what its person has claimed on the sheet's {@code today}: as it counts in
     * the live totals (see {@link #countsLive}). */
    public static boolean countsClaimed(Claim claim, Person person, LocalDate today) {
        return countsLive(claim, person, today);
    }

    /** Whether the claim would count in the live totals on the sheet's {@code today} were its status
     * {@code status} (see {@link #countsLive}). */
    public static boolean countsAs(Claim claim, ClaimStatus status, Person person, LocalDate today) {
        if (!status.counts()) return false;
        if (claim.getStatus() == ClaimStatus.SETTLED && claim.isDeferredOn(today)) return false;
        if (!status.isOpen()) return true;
        if (claim.isDeferredOn(today)) return false;
        return claim.isStated() || !person.isRetiredOn(today);
    }

    /** What a sheet's figures are worked out from: its today, the policy date, rates and dated settings in
     * force, and its claims. {@link #live} is the sheet as it stands; a sheet as it stood on an earlier
     * date gives its own. */
    public interface Basis {

        /** The sheet's today; null when there is no sheet. */
        LocalDate today();

        /** The policy date in force (see {@link Sheet#policyDateInForce()}); null when there is no sheet. */
        LocalDate policyDateInForce();

        /** The grade's rate in force on the date (see {@link RateRepository#findInForce}), or else the
         * nearest parent sheet's (see {@link RateChain}). */
        Optional<Rate> rateInForce(String grade, LocalDate date);

        /** The grade's rate in force on the date for the claim (see {@link RateRepository#inForce(java.util.List,
         * String, LocalDate, long)}): a rate saved forward-only after the claim was added does not apply to it. */
        Optional<Rate> rateFor(Claim claim, String grade, LocalDate date);

        /** The floor in force on the date; 0.00 when there is no sheet. */
        BigDecimal floorOn(LocalDate date);

        /** The late percent in force on the date. */
        BigDecimal latePctOn(LocalDate date);

        /** The cap in force on the sheet's today; null when there is none. */
        BigDecimal cap();

        /** The person's cut total: their cuts across every pool they are a member of, added up; 0.00 by default. */
        default BigDecimal cutOf(Long personId) {
            return zero();
        }

        /** The person's claim with the number. */
        Optional<Claim> claimNumbered(Long personId, String number);

        /** The person with the id. */
        Optional<Person> personOf(Long personId);

        /** The grade's grace days in force on the date; null when it has none then. */
        Integer graceOn(String grade, LocalDate date);

        /** The shares of the sheet's claims, in the order they were given; none by default. */
        default List<ClaimShare> shares() {
            return List.of();
        }

        /** The rounding mode in force on the date; null when there is none then. */
        RoundingMode roundingOn(LocalDate date);

        /** The date a claim's lateness counts from: its due-by plus the grace days in force on the claim's
         * date of its person's grade in force then (its due-by when that grade has none). */
        default LocalDate overdueFrom(Claim claim) {
            LocalDate dueBy = claim.getDueBy();
            if (dueBy == null) return null;
            Integer grace = personOf(claim.getPersonId()).map(p -> p.gradeOn(claim.getDate()))
                    .map(grade -> graceOn(grade, claim.getDate())).orElse(null);
            return grace == null ? dueBy : dueBy.plusDays(grace);
        }
    }

    /** The sheet as it stands. */
    public Basis live(String sheetId) {
        return new Basis() {
            private Map<Long, BigDecimal> cuts;

            private Sheet sheet() {
                return sheets.findById(sheetId).orElse(null);
            }

            @Override
            public LocalDate today() {
                Sheet sheet = sheet();
                return sheet == null ? null : PreviewDate.shown(sheet.getToday());
            }

            @Override
            public LocalDate policyDateInForce() {
                Sheet sheet = sheet();
                return sheet == null ? null : sheet.policyDateInForce();
            }

            @Override
            public Optional<Rate> rateInForce(String grade, LocalDate date) {
                return rates.inForce(sheetId, grade, date);
            }

            @Override
            public Optional<Rate> rateFor(Claim claim, String grade, LocalDate date) {
                return rates.inForce(sheetId, grade, date, claim.addedMark());
            }

            @Override
            public BigDecimal floorOn(LocalDate date) {
                return sheet() == null ? zero() : dated.floorOn(sheetId, date);
            }

            @Override
            public BigDecimal latePctOn(LocalDate date) {
                return dated.latePctOn(sheetId, date);
            }

            @Override
            public BigDecimal cap() {
                return sheet() == null ? null : dated.cap(sheetId);
            }

            @Override
            public BigDecimal cutOf(Long personId) {
                if (cuts == null) cuts = pools.cutTotalsNow(sheetId);
                return cuts.getOrDefault(personId, zero());
            }

            @Override
            public Optional<Claim> claimNumbered(Long personId, String number) {
                return claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, number);
            }

            @Override
            public Optional<Person> personOf(Long personId) {
                return people.findById(personId);
            }

            @Override
            public Integer graceOn(String grade, LocalDate date) {
                return dated.gradeGraceOn(sheetId, grade, date);
            }

            @Override
            public RoundingMode roundingOn(LocalDate date) {
                return dated.roundingOn(sheetId, date);
            }

            @Override
            public List<ClaimShare> shares() {
                return shares.findBySheetIdOrderByIdAsc(sheetId);
            }
        };
    }

    /** {@code base}, but with the grade's rate {@code pct} in force on every date. */
    public static Basis withRate(Basis base, String grade, BigDecimal pct) {
        Rate rate = new Rate(null, grade, null, null, pct);
        return new Basis() {
            @Override
            public LocalDate today() {
                return base.today();
            }

            @Override
            public LocalDate policyDateInForce() {
                return base.policyDateInForce();
            }

            @Override
            public Optional<Rate> rateInForce(String g, LocalDate date) {
                return grade.equals(g) ? Optional.of(rate) : base.rateInForce(g, date);
            }

            @Override
            public Optional<Rate> rateFor(Claim claim, String g, LocalDate date) {
                return grade.equals(g) ? Optional.of(rate) : base.rateFor(claim, g, date);
            }

            @Override
            public BigDecimal floorOn(LocalDate date) {
                return base.floorOn(date);
            }

            @Override
            public BigDecimal latePctOn(LocalDate date) {
                return base.latePctOn(date);
            }

            @Override
            public BigDecimal cap() {
                return base.cap();
            }

            @Override
            public BigDecimal cutOf(Long personId) {
                return base.cutOf(personId);
            }

            @Override
            public Optional<Claim> claimNumbered(Long personId, String number) {
                return base.claimNumbered(personId, number);
            }

            @Override
            public Optional<Person> personOf(Long personId) {
                return base.personOf(personId);
            }

            @Override
            public Integer graceOn(String g, LocalDate date) {
                return base.graceOn(g, date);
            }

            @Override
            public RoundingMode roundingOn(LocalDate date) {
                return base.roundingOn(date);
            }

            @Override
            public List<ClaimShare> shares() {
                return base.shares();
            }
        };
    }

    /** The person's due-total on {@code basis} from those of {@code theirs} that count on its today (see
     * {@link #countsLive}): the sum of what is due on them as each shows it, at most the cap less their cut total; null (n/a)
     * when none has a figure and no rate of their grade is in force on its today. */
    public BigDecimal dueTotalOf(List<Claim> theirs, Person person, Basis basis) {
        LocalDate today = basis.today();
        BigDecimal due = zero();
        boolean any = false;
        for (Claim claim : theirs) {
            if (!countsLive(claim, person, today)) continue;
            BigDecimal figure = shownOf(claim, person, basis);
            if (figure == null) continue;
            due = due.add(figure);
            any = true;
        }
        boolean rated = today != null && basis.rateInForce(person.gradeOn(today), today).isPresent();
        return any || rated ? cappedOf(due, basis.cap(), basis.cutOf(person.getId())) : null;
    }

    /** Whether the claim shows the figure fixed when it was settled at a rate: it is SETTLED, or was
     * voided once settled, and has one. */
    private static boolean frozen(Claim claim) {
        return claim.getSettledDue() != null
                && (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID);
    }

    /** The figure the claim shows and totals now: what was fixed when it was settled at a rate (kept
     * once it is voided);
     * otherwise the figure a statement bound it to (PENDING or not); otherwise what is due on it at its person's rate.
     * Null when it has no figure. */
    public BigDecimal figureOf(Claim claim, Person person) {
        return figureOf(claim, person, live(claim.getSheetId()));
    }

    /** The figure the claim shows on {@code basis} (see {@link #figureOf(Claim, Person)}). */
    public BigDecimal figureOf(Claim claim, Person person, Basis basis) {
        if (frozen(claim)) return claim.getSettledDue();
        if (claim.isStated()) return claim.getStatedDue();
        return dueOf(claim, person, basis);
    }

    /** What is due on the claim as it shows and totals now: what was fixed when it was settled at a
     * rate; otherwise, while a statement binds it, what was due on it as it showed then, late charge
     * included; otherwise its live due on its figure (see {@link #liveDueOf}); null when it has no figure. */
    public BigDecimal shownOf(Claim claim, Person person) {
        return shownOf(claim, person, live(claim.getSheetId()));
    }

    /** What is due on the claim as it shows on {@code basis} (see {@link #shownOf(Claim, Person)}). */
    public BigDecimal shownOf(Claim claim, Person person, Basis basis) {
        BigDecimal figure = figureOf(claim, person, basis);
        if (frozen(claim)) return figure;
        if (claim.isStated() && claim.getStatedShown() != null) return claim.getStatedShown();
        return liveDueOf(claim, figure, basis);
    }

    /** The live due on the claim whose figure (at its rate, or as a statement bound it) is
     * {@code figure}: that figure, at least the sheet's floor in force on the claim's date, plus the late
     * charge worked out on that floored figure (see {@link #lateChargeOf}); a correction has neither floor
     * nor charge. Null when {@code figure} is. */
    public BigDecimal liveDueOf(Claim claim, BigDecimal figure) {
        return liveDueOf(claim, figure, live(claim.getSheetId()));
    }

    private BigDecimal liveDueOf(Claim claim, BigDecimal figure, Basis basis) {
        if (figure == null) return null;
        if (claim.isCorrection()) return figure;
        BigDecimal floored = floored(claim, figure, basis);
        return floored.add(lateChargeOf(claim, floored, basis));
    }

    /** The figure a statement taken on {@code basis} binds the claim to: what was fixed when it was
     * settled at a rate; otherwise its figure (see {@link #figureOf(Claim, Person, Basis)}), at least the
     * sheet's floor in force on its date (a correction has no floor). Null when it has no figure. */
    public BigDecimal statedFigureOf(Claim claim, Person person, Basis basis) {
        BigDecimal figure = figureOf(claim, person, basis);
        if (figure == null || frozen(claim) || claim.isCorrection()) return figure;
        return floored(claim, figure, basis);
    }

    /** {@code due}, at least the sheet's floor in force on the claim's date. */
    private static BigDecimal floored(Claim claim, BigDecimal due, Basis basis) {
        return due.max(basis.floorOn(claim.getDate()));
    }

    /** The late charge on an OPEN or PENDING claim whose figure is {@code figure}: for every full 30 days the claim is overdue (past its due-by, fixed when it was added, plus
     * any grace of its grade (see {@link Basis#overdueFrom}), on the sheet's today), the sheet's late percent
     * in force on that boundary date (that date plus 30, 60, 90 ... days) of that figure plus the charges of the boundaries before it (compounding), rounded to two decimals as the claim rounds, the charges added up (a boundary whose percent is 0.00 adds nothing) (see {@link #roundingOf}); 0.00 on a claim not OPEN or
     * PENDING, or not overdue (a deferred claim is not), on a correction and on a waived claim. A claim a statement binds shows the charge it had then instead (see {@link #shownOf}). */
    public BigDecimal lateChargeOf(Claim claim, BigDecimal figure) {
        return lateChargeOf(claim, figure, live(claim.getSheetId()));
    }

    private BigDecimal lateChargeOf(Claim claim, BigDecimal figure, Basis basis) {
        if (!claim.getStatus().isOpen()) return zero();
        return overdueChargeOf(claim, figure, basis);
    }

    /** What would be due on the claim now were it an OPEN claim bound by no statement: its due at its
     * person's rate (see {@link #dueOf}), at least the sheet's floor in force on its date, plus the late
     * charge it would have then on that floored figure (a correction has neither); null when it would
     * have no figure. */
    public BigDecimal openShownOf(Claim claim, Person person) {
        Basis basis = live(claim.getSheetId());
        BigDecimal figure = dueOf(claim, person, basis);
        if (figure == null || claim.isCorrection()) return figure;
        BigDecimal floored = floored(claim, figure, basis);
        return floored.add(overdueChargeOf(claim, floored, basis));
    }

    /** The late charge on a claim whose figure is {@code figure}, whatever its status (see {@link #lateChargeOf}). */
    private BigDecimal overdueChargeOf(Claim claim, BigDecimal figure, Basis basis) {
        if (claim.isCorrection() || claim.isWaived()) return zero();
        LocalDate today = basis.today();
        if (today == null || claim.isDeferredOn(today)) return zero();
        LocalDate from = basis.overdueFrom(claim);
        long periods = Math.max(0, ChronoUnit.DAYS.between(from, today)) / 30;
        RoundingMode rounding = roundingOf(claim, basis);
        BigDecimal charge = zero();
        for (long k = 1; k <= periods; k++) {
            BigDecimal pct = basis.latePctOn(from.plusDays(30 * k));
            if (pct.signum() == 0) continue;
            charge = charge.add(figure.add(charge).multiply(pct).movePointLeft(2).setScale(2, rounding));
        }
        return charge;
    }

    /** What is due on the claim at its person's rate in force on the claim's date: amount times pct,
     * divided by 100, rounded to two decimals as the claim rounds (see {@link #roundingOf}) — the rate
     * figure alone, before any late charge or floor (see {@link #liveDueOf}); null when no
     * rate of the grade in force for them on the claim's date applies then. */
    public BigDecimal dueOf(Claim claim, Person person) {
        return dueOf(claim, person, live(claim.getSheetId()));
    }

    private BigDecimal dueOf(Claim claim, Person person, Basis basis) {
        RoundingMode rounding = roundingOf(claim, basis);
        return basis.rateFor(claim, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding))
                .orElse(null);
    }

    /** How the claim's live figures round: by the rounding mode in force on its date when there is one;
     * else half up when it is dated before the sheet's policy date in force, half to even when on or
     * after it. A correction rounds by the date of the claim it corrects. */
    private static RoundingMode roundingOf(Claim claim, Basis basis) {
        LocalDate dated = claim.getDate();
        if (claim.isCorrection() && claim.getCorrects() != null) {
            dated = basis.claimNumbered(claim.getPersonId(), claim.getCorrects()).map(Claim::getDate).orElse(dated);
        }
        RoundingMode mode = basis.roundingOn(dated);
        if (mode != null) return mode;
        LocalDate policyDate = basis.policyDateInForce();
        if (policyDate == null) return RoundingMode.HALF_EVEN;
        return dated.isBefore(policyDate) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String text(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
