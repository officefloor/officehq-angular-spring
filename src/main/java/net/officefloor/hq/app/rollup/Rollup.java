package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.setting.SheetSettingRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total (late charges
 * included), and the sheet's
 * claimed-total and due-total. A change takes a snapshot before it alters anything, then
 * {@link #recordChanges} audits, after the change's own lines, each person whose figures moved (in the
 * order they were added) and then the sheet's totals when they moved.
 */
@Service
public class Rollup {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person's figures; a null due-total is n/a. */
    record Figures(String name, BigDecimal claimed, BigDecimal dueTotal) {
        boolean sameAs(Figures other) {
            return other != null && same(claimed, other.claimed) && same(dueTotal, other.dueTotal);
        }
    }

    /** The sheet's figures at one moment: each person's, by id in the order they were added, and the totals. */
    public record Snapshot(String sheetId, Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateRepository rates;
    private final SheetRepository sheets;
    private final SheetSettingRepository settings;
    private final Audit audit;

    public Rollup(PersonRepository people, ClaimRepository claims, RateRepository rates, SheetRepository sheets,
            SheetSettingRepository settings, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's figures as the store holds them now, within the caller's transaction. */
    public Snapshot snapshot(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        BigDecimal floor = SettingService.floorOf(settings, sheetId);
        int terms = SettingService.termsOf(settings, sheetId);
        BigDecimal latePct = SettingService.latePctOf(settings, sheetId);
        LocalDate policyDate = Rounding.policyDate(SettingService.policyDateOf(settings, sheetId), today);
        Map<Grade, List<Rate>> ratesByGrade = new LinkedHashMap<>();
        for (Rate rate : rates.findBySheetIdInOrder(sheetId)) {
            ratesByGrade.computeIfAbsent(rate.getGrade(), g -> new ArrayList<>()).add(rate);
        }
        Map<Long, List<Claim>> claimsByPerson = new LinkedHashMap<>();
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            claimsByPerson.computeIfAbsent(claim.getPersonId(), p -> new ArrayList<>()).add(claim);
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = ZERO;
        BigDecimal dueTotal = ZERO;
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            Grade gradeToday = today == null ? null : person.gradeOn(today);
            BigDecimal claimed = ZERO;
            BigDecimal due = ZERO;
            boolean keptFigure = false;
            List<Claim> theirs = claimsByPerson.getOrDefault(person.getId(), List.of());
            for (Claim claim : theirs) {
                if (!counts(person, claim)) continue;
                claimed = claimed.add(claim.getAmount());
                // A claim's live figure is at the grade in force on its date.
                Grade grade = person.gradeOn(claim.getDate());
                RoundingMode rounding = Rounding.of(Rounding.dateOf(claim, corrected(claim, theirs)), policyDate);
                BigDecimal figure = figure(claim, ratesByGrade.getOrDefault(grade, List.of()), floor, rounding);
                BigDecimal owed = LateCharge.dueOf(claim, figure, terms, today, latePct, rounding);
                if (owed != null) due = due.add(owed);
                if (claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)) {
                    keptFigure = true;
                }
                // A live figure at a grade other than today's is not undone by today's grade having no rate.
                if (figure != null && grade != gradeToday) keptFigure = true;
            }
            // n/a when no rate of the grade in force today is in force today and no claim keeps a figure.
            BigDecimal personDue = (today == null
                    || inForce(ratesByGrade.getOrDefault(gradeToday, List.of()), today) == null) && !keptFigure
                            ? null : due;
            figures.put(person.getId(), new Figures(person.getName(), claimed, personDue));
            claimedTotal = claimedTotal.add(claimed);
            if (personDue != null) dueTotal = dueTotal.add(personDue);
        }
        return new Snapshot(sheetId, figures, claimedTotal, dueTotal);
    }

    /** Audit, once the transaction commits, the figures that moved since the snapshot was taken. */
    public void recordChanges(String user, Snapshot before) {
        Snapshot after = snapshot(before.sheetId());
        String prefix = " sheet=" + before.sheetId() + " by=" + user;
        after.people().forEach((id, now) -> {
            if (!now.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP" + prefix + " person=" + now.name() + " claimed=" + text(now.claimed())
                        + " due-total=" + text(now.dueTotal()));
            }
        });
        if (!same(before.claimedTotal(), after.claimedTotal()) || !same(before.dueTotal(), after.dueTotal())) {
            audit.recordOnCommit("TOTALS" + prefix + " claimed-total=" + text(after.claimedTotal())
                    + " due-total=" + text(after.dueTotal()));
        }
    }

    /** Only open (PENDING included) and settled claims count toward what is claimed and due; a held
     *  person's open claims do not. */
    private static boolean counts(Person person, Claim claim) {
        return claim.getStatus() == ClaimStatus.SETTLED
                || (claim.getStatus().isOpen() && !person.isHeld());
    }

    /** The claim's figure, as ClaimService.figure works it out: the settled due, else the stated due,
     *  (unless PENDING), else the live due at the grade's rate in force on its date, rounded as the claim rounds, at least
     *  the floor unless a correction; null when that is n/a. */
    private static BigDecimal figure(Claim claim, List<Rate> gradeRates, BigDecimal floor, RoundingMode rounding) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        Rate rate = inForce(gradeRates, claim.getDate());
        if (rate == null) return null;
        BigDecimal due = claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2, rounding);
        return claim.isCorrection() ? due : due.max(floor);
    }

    /** Of the person's claims, the one a correction corrects by number; null for any other claim. */
    private static Claim corrected(Claim claim, List<Claim> theirs) {
        if (!claim.isCorrection() || claim.getCorrects() == null) return null;
        for (Claim other : theirs) {
            if (claim.getCorrects().equals(other.getNumber())) return other;
        }
        return null;
    }

    /** Of the grade's rates (from the start first, then by from-date), the one in force on the date. */
    private static Rate inForce(List<Rate> gradeRates, LocalDate date) {
        Rate inForce = null;
        for (Rate rate : gradeRates) {
            if (rate.appliesBy(date)) inForce = rate;
        }
        return inForce;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private static String text(BigDecimal figure) {
        return figure == null ? "n/a" : figure.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
