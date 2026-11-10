package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed, due-total and overdue-total
 * (the figures of their overdue claims that count, not capped), and the sheet's totals of each. A change takes a {@link #snapshot} before it alters anything
 * and calls {@link #recordChanges} after its own audit lines, which appends one ROLLUP line per
 * person whose figures moved (people in the order they were added; a new person counts as moved),
 * then a TOTALS line when any of the sheet's totals moved. A held person's OPEN claims count in none of
 * these (nor their PENDING ones), nor in a period's due-total; their SETTLED claims still do. A live due is never below
 * the sheet's floor; an overdue claim's figure has its late charge added on top. A person's
 * due-total is at most the sheet's cap, when it has one.
 */
@Service
public class RollupService {

    /** A person's figures: claimed, due-total (null for n/a) and overdue-total. */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** The sheet's figures: each person's by id, in the order they were added. */
    public record Snapshot(Map<Long, Figures> people) {

        public BigDecimal claimedTotal() {
            return people.values().stream().map(Figures::claimed).reduce(zero(), BigDecimal::add);
        }

        public BigDecimal dueTotal() {
            return people.values().stream().map(Figures::dueTotal).filter(Objects::nonNull).reduce(zero(),
                    BigDecimal::add);
        }

        public BigDecimal overdueTotal() {
            return people.values().stream().map(Figures::overdueTotal).reduce(zero(), BigDecimal::add);
        }
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures as the store holds them now. */
    public Snapshot snapshot(String sheetId) {
        Sheet sheet = sheet(sheetId);
        LocalDate today = sheet.getToday();
        BigDecimal floor = sheet.getFloor();
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Map<Long, Figures> figures = new LinkedHashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            BigDecimal claimed = zero();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            boolean anyFigure = false;
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !person.counts(claim)) {
                    continue;
                }
                claimed = claimed.add(claim.getAmount());
                BigDecimal figure = figure(sheet, sheetRates, sheetClaims, floor, person, claim);
                if (figure != null) {
                    anyFigure = true;
                    due = due.add(figure);
                    if (overdue(claim, today)) {
                        overdue = overdue.add(figure);
                    }
                }
            }
            // as on screen: n/a when the grade in force today has no rate today and no claim that
            // counts has a figure
            boolean none = inForce(sheetRates, person.gradeOn(today), today).isEmpty() && !anyFigure;
            figures.put(person.getId(), new Figures(person.getName(), claimed, none ? null : sheet.capped(due),
                    overdue));
        }
        return new Snapshot(figures);
    }

    /**
     * The due of the claims that count whose date falls in from..to (both inclusive), each at the
     * figure the screen shows for it now.
     */
    public BigDecimal dueBetween(String sheetId, LocalDate from, LocalDate to) {
        Sheet sheet = sheet(sheetId);
        BigDecimal floor = sheet.getFloor();
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        BigDecimal due = zero();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            for (Claim claim : sheetClaims) {
                if (!claim.getPersonId().equals(person.getId()) || !person.counts(claim)
                        || claim.getDate().isBefore(from) || claim.getDate().isAfter(to)) {
                    continue;
                }
                BigDecimal figure = figure(sheet, sheetRates, sheetClaims, floor, person, claim);
                if (figure != null) {
                    due = due.add(figure);
                }
            }
        }
        return due;
    }

    /** Record, once the change commits, the figures that moved since the snapshot taken before it. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        String prefix = " sheet=" + sheetId + " by=" + user;
        boolean moved = false;
        for (Map.Entry<Long, Figures> entry : after.people().entrySet()) {
            Figures now = entry.getValue();
            Figures was = before.people().get(entry.getKey());
            if (was != null && same(was.claimed(), now.claimed()) && same(was.dueTotal(), now.dueTotal())
                    && same(was.overdueTotal(), now.overdueTotal())) {
                continue;
            }
            moved = true;
            audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + money(now.claimed())
                    + " due-total=" + money(now.dueTotal()) + " overdue-total=" + money(now.overdueTotal()));
        }
        if (moved && !(same(before.claimedTotal(), after.claimedTotal())
                && same(before.dueTotal(), after.dueTotal()) && same(before.overdueTotal(), after.overdueTotal()))) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + money(after.claimedTotal()) + " due-total="
                    + money(after.dueTotal()) + " overdue-total=" + money(after.overdueTotal()));
        }
    }

    /**
     * A claim's figure as the screen shows it: the due fixed when settled, else the one bound by the
     * last statement (not while PENDING), else its live due (rounded as the claim rounds, see {@link Rounding}) raised to the floor (a correction's is not), with its late charge added on top;
     * null when none.
     */
    private static BigDecimal figure(Sheet sheet, List<Rate> sheetRates, List<Claim> sheetClaims, BigDecimal floor,
            Person person, Claim claim) {
        if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal figure = claim.isStated() && claim.getStatus() != ClaimStatus.PENDING ? claim.getStatedDue()
                : inForce(sheetRates, person.gradeOn(claim.getDate()), claim.getDate()).map(Rate::getPct)
                        .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2,
                                Rounding.mode(sheet, Rounding.dateOf(claim, sheetClaims))))
                        .map(due -> claim.isCorrection() ? due : due.max(floor))
                        .orElse(null);
        return LateCharge.withCharge(sheet, claim, figure);
    }

    /** Whether a claim is overdue on the sheet's today: OPEN or PENDING, and past its due-by. */
    private static boolean overdue(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen() && today != null && claim.getDueBy() != null
                && claim.getDueBy().isBefore(today);
    }

    private Sheet sheet(String sheetId) {
        return sheets.findById(sheetId).orElseGet(() -> new Sheet(sheetId, null, null, LocalDate.now()));
    }

    /** The grade's rate in force on a date, as {@link RateRepository#findInForce} picks it. */
    private static Optional<Rate> inForce(List<Rate> sheetRates, String grade, LocalDate date) {
        return sheetRates.stream().filter(r -> r.getGrade().equals(grade))
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max((a, b) -> a.getFromDate() == null ? -1 : b.getFromDate() == null ? 1
                        : a.getFromDate().compareTo(b.getFromDate()))
                .filter(r -> r.runsThrough(date));
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static String money(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
