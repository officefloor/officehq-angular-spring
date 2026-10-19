package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed, due-total (at most the sheet's cap) and
 * overdue-total, and the sheet's claimed-total, due-total and overdue-total. A change takes them before it alters anything and hands them back after, and
 * every figure it moved is written to the audit after the change's own lines. Called within the change's
 * transaction.
 */
@Service
public class RollupService {

    /** Shown in place of a due-total whose grade has no rate. */
    private static final String NO_RATE = "n/a";

    /** A person's figures, as the screen shows them. */
    public record Figures(String name, String claimed, String dueTotal, String overdueTotal) {}

    /** The sheet's figures at one moment: each person's by id, in the order they were added, and the totals. */
    public record Snapshot(Map<Long, Figures> people, String claimedTotal, String dueTotal, String overdueTotal) {}

    /** What a claim's late charge goes by: the sheet's today (none when there is no such sheet) and its late
     * percent; and the date its rounding goes by, the policy date or else the sheet's today. */
    private record Lateness(LocalDate today, BigDecimal latePct, LocalDate policyDate) {

        static Lateness of(Sheet sheet) {
            return sheet == null ? new Lateness(null, BigDecimal.ZERO, null)
                    : new Lateness(sheet.getToday(), sheet.getLatePct(), sheet.roundingDate());
        }

        /** How a claim's live figures round, by the date they go by: one before the policy date rounds half up,
         * one on or after it half to even. */
        RoundingMode rounding(LocalDate roundsBy) {
            return policyDate != null && roundsBy.isBefore(policyDate) ? RoundingMode.HALF_UP
                    : RoundingMode.HALF_EVEN;
        }
    }

    /** The days in each period an overdue claim gains its late percent for. */
    private static final int LATE_PERIOD_DAYS = 30;

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, RateRepository rates,
            SheetRepository sheets, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures now; none (an empty sheet's) when there is no such sheet. */
    public Snapshot snapshot(String sheetId) {
        Lateness late = latenessOf(sheetId);
        LocalDate today = late.today();
        BigDecimal floor = floorOf(sheetId);
        Map<Long, List<Claim>> byPerson = new LinkedHashMap<>();
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        for (Claim claim : all) {
            byPerson.computeIfAbsent(claim.getPersonId(), k -> new ArrayList<>()).add(claim);
        }
        Map<String, LocalDate> datesByNumber = datesByNumber(all);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal dueTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal overdueTotal = BigDecimal.ZERO.setScale(2);
        Map<String, List<Rate>> byGrade = ratesByGrade(sheetId);
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            List<Claim> counted = byPerson.getOrDefault(person.getId(), List.of()).stream()
                    .filter(c -> counts(c, person)).toList();
            BigDecimal claimed = BigDecimal.ZERO.setScale(2);
            BigDecimal due = BigDecimal.ZERO.setScale(2);
            BigDecimal overdue = BigDecimal.ZERO.setScale(2);
            boolean frozen = false;
            for (Claim claim : counted) {
                claimed = claimed.add(claim.getAmount().setScale(2));
                BigDecimal figure = dueOf(claim, ratesOn(person, claim.getDate(), byGrade), floor, late,
                        roundsBy(claim, datesByNumber));
                frozen |= figure != null;
                if (figure != null) {
                    due = due.add(figure.setScale(2));
                    if (overdue(claim, today)) {
                        overdue = overdue.add(figure.setScale(2));
                    }
                }
            }
            // Like the screen: with no rate in force today and no claim having a figure (kept, or at a rate that
            // applies on its date), the due-total is n/a.
            boolean rated = rated(person, counted, today, byGrade);
            if (sheet != null) {
                due = sheet.capped(due);
            }
            String personDue = rated || frozen ? due.toPlainString() : NO_RATE;
            claimedTotal = claimedTotal.add(claimed);
            if (rated || frozen) {
                dueTotal = dueTotal.add(due);
            }
            overdueTotal = overdueTotal.add(overdue);
            figures.put(person.getId(),
                    new Figures(person.getName(), claimed.toPlainString(), personDue, overdue.toPlainString()));
        }
        return new Snapshot(figures, claimedTotal.toPlainString(), dueTotal.toPlainString(),
                overdueTotal.toPlainString());
    }

    /** A person's due-total held to the sheet's cap; none (n/a) is not capped. */
    public BigDecimal capped(String sheetId, BigDecimal dueTotal) {
        return sheets.findById(sheetId).map(s -> s.capped(dueTotal)).orElse(dueTotal);
    }

    /** The due-total of the sheet's claims that count (OPEN and PENDING ones within a hold, unbound, do not) dated from and to, inclusive: the sum of their dues,
     * late charges included, n/a ones skipped. */
    public BigDecimal dueTotal(String sheetId, LocalDate from, LocalDate to) {
        BigDecimal floor = floorOf(sheetId);
        Lateness late = latenessOf(sheetId);
        Map<String, List<Rate>> byGrade = ratesByGrade(sheetId);
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            byId.put(person.getId(), person);
        }
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<String, LocalDate> datesByNumber = datesByNumber(all);
        for (Claim claim : all) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !counts(claim, person)
                    || claim.getDate().isBefore(from) || claim.getDate().isAfter(to)) {
                continue;
            }
            BigDecimal figure = dueOf(claim, ratesOn(person, claim.getDate(), byGrade), floor, late,
                    roundsBy(claim, datesByNumber));
            if (figure != null) {
                total = total.add(figure.setScale(2));
            }
        }
        return total;
    }

    /**
     * Write to the audit, once the change commits, one ROLLUP line for each person whose figures moved since
     * {@code before} (a person new since then counts as moved), in the order they were added, then a TOTALS
     * line when the sheet's totals moved.
     */
    public void recordChanges(String user, String sheetId, Snapshot before) {
        claims.flush();
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        for (Map.Entry<Long, Figures> e : after.people().entrySet()) {
            Figures now = e.getValue();
            Figures was = before.people().get(e.getKey());
            if (was != null && was.claimed().equals(now.claimed()) && was.dueTotal().equals(now.dueTotal())
                    && was.overdueTotal().equals(now.overdueTotal())) {
                continue;
            }
            audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + now.claimed()
                    + " due-total=" + now.dueTotal() + " overdue-total=" + now.overdueTotal());
        }
        if (!Objects.equals(before.claimedTotal(), after.claimedTotal())
                || !Objects.equals(before.dueTotal(), after.dueTotal())
                || !Objects.equals(before.overdueTotal(), after.overdueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + after.claimedTotal()
                    + " due-total=" + after.dueTotal() + " overdue-total=" + after.overdueTotal());
        }
    }

    /** The figure a claim has now: what it was settled at, else what its person's last statement bound it to
     * (while not PENDING), else what is due at the rate of the grade its person has on its date, raised to the sheet's floor; none
     * when that is n/a. */
    public BigDecimal figureOf(Claim claim, Person person) {
        return figureOf(claim, gradeRates(claim, person), floorOf(claim.getSheetId()),
                latenessOf(claim.getSheetId()).rounding(roundsBy(claim)));
    }

    /** What is due on a claim now: its figure, plus the late charge an overdue claim has on it; none when the
     * figure is n/a. A settled claim's due is the figure it was settled at, the charge it had then frozen in. */
    public BigDecimal dueOf(Claim claim, Person person) {
        return dueOf(claim, gradeRates(claim, person), floorOf(claim.getSheetId()), latenessOf(claim.getSheetId()),
                roundsBy(claim));
    }

    /** The date a claim's rounding goes by: a correction's is the date of the claim it corrects, any other's its
     * own. */
    private LocalDate roundsBy(Claim claim) {
        return claim.getCorrects() == null ? claim.getDate()
                : roundsBy(claim, datesByNumber(claims.findBySheetIdAndPersonId(claim.getSheetId(),
                        claim.getPersonId())));
    }

    private static LocalDate roundsBy(Claim claim, Map<String, LocalDate> datesByNumber) {
        return claim.getCorrects() == null ? claim.getDate()
                : datesByNumber.getOrDefault(claim.getCorrects(), claim.getDate());
    }

    /** The dates of the claims given that have a number, by number. */
    private static Map<String, LocalDate> datesByNumber(List<Claim> given) {
        Map<String, LocalDate> by = new HashMap<>();
        for (Claim claim : given) {
            if (claim.getNumber() != null) {
                by.put(claim.getNumber(), claim.getDate());
            }
        }
        return by;
    }

    /**
     * Whether a person's due-total has a rate to go by on the sheet's today: the grade they have in force today,
     * or one they had on the date of a claim of theirs that counts, has a rate in force today.
     */
    public boolean rated(Person person, List<Claim> counted, LocalDate today) {
        return rated(person, counted, today, ratesByGrade(person.getSheetId()));
    }

    private static boolean rated(Person person, List<Claim> counted, LocalDate today,
            Map<String, List<Rate>> byGrade) {
        if (today == null) {
            return false;
        }
        Set<String> grades = new HashSet<>();
        grades.add(person.gradeOn(today));
        for (Claim claim : counted) {
            grades.add(person.gradeOn(claim.getDate()));
        }
        return grades.stream().anyMatch(g -> Rate.inForce(byGrade.getOrDefault(g, List.of()), today).isPresent());
    }

    /** The rates of the grade the claim's person has on the claim's date. */
    private List<Rate> gradeRates(Claim claim, Person person) {
        return rates.findBySheetIdAndGrade(claim.getSheetId(), person.gradeOn(claim.getDate()));
    }

    /** The rates of the grade a person has on a date. */
    private static List<Rate> ratesOn(Person person, LocalDate date, Map<String, List<Rate>> byGrade) {
        return byGrade.getOrDefault(person.gradeOn(date), List.of());
    }

    /** The sheet's rates by grade. */
    private Map<String, List<Rate>> ratesByGrade(String sheetId) {
        Map<String, List<Rate>> by = new HashMap<>();
        for (Rate rate : rates.findBySheetId(sheetId)) {
            by.computeIfAbsent(rate.getGrade(), k -> new ArrayList<>()).add(rate);
        }
        return by;
    }

    private static BigDecimal dueOf(Claim claim, List<Rate> gradeRates, BigDecimal floor, Lateness late,
            LocalDate roundsBy) {
        RoundingMode rounding = late.rounding(roundsBy);
        BigDecimal figure = figureOf(claim, gradeRates, floor, rounding);
        return figure == null ? null : figure.add(lateCharge(claim, figure, late, rounding));
    }

    /**
     * The late charge on a claim: the late percent of its figure for every full 30 days it is overdue (OPEN or
     * PENDING, and its due-by, fixed when it was added, before the sheet's today), rounded to two decimals as the claim rounds.
     * A statement never binds it: it follows the sheet's today. A correction has none.
     */
    private static BigDecimal lateCharge(Claim claim, BigDecimal figure, Lateness late, RoundingMode rounding) {
        if (!claim.getStatus().isOpen() || late.today() == null || claim.isCorrection()) {
            return BigDecimal.ZERO.setScale(2);
        }
        long overdueDays = ChronoUnit.DAYS.between(claim.getDueBy(), late.today());
        long periods = overdueDays > 0 ? overdueDays / LATE_PERIOD_DAYS : 0;
        return figure.multiply(late.latePct()).multiply(BigDecimal.valueOf(periods))
                .divide(BigDecimal.valueOf(100), 2, rounding);
    }

    private static BigDecimal figureOf(Claim claim, List<Rate> gradeRates, BigDecimal floor, RoundingMode rounding) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return bound(claim) ? claim.getStatedDue() : liveDueOf(claim, gradeRates, floor, rounding);
    }

    /** What is due on a claim at the rate its grade has in force on the claim's date, rounded to two decimals as
     * the claim rounds, and at least the floor (a correction has no floor); none when no rate of the grade applies
     * then. */
    private static BigDecimal liveDueOf(Claim claim, List<Rate> gradeRates, BigDecimal floor, RoundingMode rounding) {
        BigDecimal amount = claim.getAmount();
        return Rate.inForce(gradeRates, claim.getDate())
                .map(r -> amount.multiply(r.getRate()).divide(BigDecimal.valueOf(100), 2, rounding))
                .map(due -> claim.isCorrection() ? due : due.max(floor.setScale(2)))
                .orElse(null);
    }

    private Lateness latenessOf(String sheetId) {
        return Lateness.of(sheets.findById(sheetId).orElse(null));
    }

    /** The sheet's floor; 1.00, the one a sheet starts with, when there is no such sheet. */
    private BigDecimal floorOf(String sheetId) {
        return sheets.findById(sheetId).map(s -> s.getFloor()).orElse(new BigDecimal("1.00"));
    }

    /** Whether a claim goes by the figure its person's last statement bound it to: a PENDING claim is not bound,
     * but shows its live figure, and is bound again if it is rejected. */
    private static boolean bound(Claim claim) {
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING;
    }

    /** Whether a claim is overdue: it is OPEN or PENDING and its due-by is before the sheet's today. */
    private static boolean overdue(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && today != null && claim.getDueBy().isBefore(today);
    }

    /** Only OPEN, PENDING and SETTLED claims count toward what is claimed and due; an OPEN or PENDING one dated
     * within one of its person's holds does not, unless it is bound by a statement. */
    public static boolean counts(Claim claim, Person person) {
        return (claim.getStatus().isOpen() && (bound(claim) || !person.heldOn(claim.getDate())))
                || claim.getStatus() == ClaimStatus.SETTLED;
    }
}
