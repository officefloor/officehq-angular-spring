package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: what each person has claimed, their due-total and
 * overdue-total, and the sheet's claimed-total, due-total and overdue-total. A change takes them
 * before it writes and has them audited after its own records, so every figure it moves is recorded.
 */
@Service
public class Rollups {

    /** Shown in place of a due-total when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    /**
     * A person's figures: what they have claimed, their due-total (null when n/a) and their
     * overdue-total (the due of their counting overdue claims), with their settled-total (the due of
     * their counting SETTLED claims that correct nothing) and how many of their counting claims are overdue, which make their score.
     */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal,
            BigDecimal settledTotal, int overdueCount) {

        /**
         * Their score as the screen shows it: their settled-total over 100, less 5 for each overdue
         * claim, rounded half to even to a whole number.
         */
        public long score() {
            return settledTotal.subtract(BigDecimal.valueOf(500L * overdueCount))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_EVEN).longValueExact();
        }

        boolean sameAs(Figures other) {
            return other != null && same(claimed, other.claimed) && same(dueTotal, other.dueTotal)
                    && same(overdueTotal, other.overdueTotal);
        }
    }

    /**
     * The sheet's figures: each person's by id, in list order, the sheet's totals, and how many of its
     * counting claims are overdue.
     */
    public record Rollup(Map<Long, Figures> people, BigDecimal claimedTotal, BigDecimal dueTotal,
            BigDecimal overdueTotal, int overdueCount) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final SheetRepository sheets;
    private final Dues dues;
    private final Audit audit;

    public Rollups(PersonRepository people, ClaimRepository claims, SheetRepository sheets,
            Dues dues, Audit audit) {
        this.people = people;
        this.claims = claims;
        this.sheets = sheets;
        this.dues = dues;
        this.audit = audit;
    }

    /** The sheet's figures as they stand now in the current transaction. */
    public Rollup take(String sheetId) {
        Map<Long, Person> sheetPeople = new LinkedHashMap<>();
        people.findBySheetIdOrderByPositionAscIdAsc(sheetId).forEach(person -> sheetPeople.put(person.getId(), person));
        return take(sheets.findById(sheetId).orElse(null), dues.live(sheetId), sheetPeople,
                claims.findBySheetIdOrderByIdDesc(sheetId));
    }

    /**
     * A sheet's figures read from what is given, held in no store or not: the sheet (null when not found;
     * its today is the day they are read on), the basis their dues are read against, its people by id in
     * list order, and its claims.
     */
    public Rollup take(Sheet sheet, Dues.Basis basis, Map<Long, Person> sheetPeople, Iterable<Claim> sheetClaims) {
        LocalDate today = sheet == null ? null : sheet.getToday();
        Map<Long, List<Claim>> byPerson = new LinkedHashMap<>();
        for (Claim claim : sheetClaims) {
            if (claim.getStatus().counts()) {
                byPerson.computeIfAbsent(claim.getPersonId(), id -> new ArrayList<>()).add(claim);
            }
        }
        // The parts of shared claims their holders' due-totals count, by holder, from the claims that
        // count for their own person; each claim's figure as read here.
        Map<Claim, BigDecimal> shared = new java.util.IdentityHashMap<>();
        Map<Long, BigDecimal> received = new LinkedHashMap<>();
        for (Map.Entry<Long, Person> entry : sheetPeople.entrySet()) {
            Person person = entry.getValue();
            for (Claim claim : byPerson.getOrDefault(entry.getKey(), List.of())) {
                if (claim.getShares().isEmpty()
                        || !person.countsLive(claim.getStatus(), claim.isStated(), claim.isDeferredOn(today), today)) {
                    continue;
                }
                BigDecimal figure = dues.due(claim, person.gradeOn(claim.getDate()), basis);
                if (figure == null) {
                    continue;
                }
                shared.put(claim, figure);
                for (var share : claim.getShares()) {
                    received.merge(share.getPersonId(), share.partOf(figure), BigDecimal::add);
                }
            }
        }
        Map<Long, Figures> figures = new LinkedHashMap<>();
        BigDecimal claimedTotal = zero();
        List<BigDecimal> dueFigures = new ArrayList<>();
        List<BigDecimal> overdueFigures = new ArrayList<>();
        int overdueCount = 0;
        for (Map.Entry<Long, Person> entry : sheetPeople.entrySet()) {
            Person person = entry.getValue();
            List<Claim> personClaims = byPerson.getOrDefault(entry.getKey(), List.of());
            BigDecimal claimed = zero();
            for (Claim claim : personClaims) {
                // What is claimed leaves out claims that correct another.
                if (person.countsLive(claim.getStatus(), claim.isStated(), claim.isDeferredOn(today), today)
                        && (claim.getCorrects() == null || claim.getCorrects().isBlank())) {
                    claimed = claimed.add(claim.getAmount());
                }
            }
            List<Claim> counting = personClaims.stream()
                    .filter(claim -> person.countsLive(claim.getStatus(), claim.isStated(), claim.isDeferredOn(today), today))
                    .toList();
            BigDecimal due = zero();
            BigDecimal overdue = zero();
            BigDecimal settled = zero();
            int personOverdue = 0;
            boolean figured = false;
            for (Claim claim : counting) {
                String grade = person.gradeOn(claim.getDate());
                boolean late = overdue(claim, sheet, grade);
                if (late) {
                    overdueCount++;
                    personOverdue++;
                }
                BigDecimal figure = dues.due(claim, grade, basis);
                if (figure != null) {
                    // A shared claim's own person counts its figure less its holders' parts.
                    due = due.add(shared.containsKey(claim) ? figure.subtract(claim.sharedPartsOf(figure)) : figure);
                    figured = true;
                    if (late) {
                        overdue = overdue.add(figure);
                    }
                    // The score's settled figures leave out claims that correct another.
                    if (claim.getStatus() == ClaimStatus.SETTLED
                            && (claim.getCorrects() == null || claim.getCorrects().isBlank())) {
                        settled = settled.add(figure);
                    }
                }
            }
            BigDecimal part = received.get(entry.getKey());
            if (part != null) {
                due = due.add(part);
                figured = true;
            }
            // As on the screen: n/a when the grade in force today has no rate, unless a claim has a
            // figure (a fixed one, or one at the grade in force on its own date).
            boolean rated = today != null && basis.rateInForce(person.gradeOn(today), today).isPresent();
            if (!rated && !figured) {
                due = null;
            }
            // A due-total is at most the sheet's cap.
            if (sheet != null) {
                due = sheet.capped(due);
            }
            figures.put(entry.getKey(), new Figures(person.getName(), claimed, due, overdue, settled, personOverdue));
            claimedTotal = claimedTotal.add(claimed);
            if (due != null) {
                dueFigures.add(due);
            }
            overdueFigures.add(overdue);
        }
        return new Rollup(figures, claimedTotal, trimmed(dueFigures), trimmed(overdueFigures), overdueCount);
    }

    /**
     * The sum of the people's figures, leaving out the single highest and the single lowest when there
     * are three or more.
     */
    static BigDecimal trimmed(List<BigDecimal> figures) {
        BigDecimal total = zero();
        for (BigDecimal figure : figures) {
            total = total.add(figure);
        }
        if (figures.size() >= 3) {
            BigDecimal highest = figures.stream().max(BigDecimal::compareTo).orElseThrow();
            BigDecimal lowest = figures.stream().min(BigDecimal::compareTo).orElseThrow();
            total = total.subtract(highest).subtract(lowest);
        }
        return total;
    }

    /**
     * Record, once the change commits, one ROLLUP line per person whose figures moved since
     * {@code before} (a person new since then counts as moved), then a TOTALS line when any of the sheet's
     * totals moved, then one ALERT line per person, in list order, whose due-total went from at or below
     * the sheet's alert threshold (or none) to above it.
     */
    public void audit(String sheetId, String user, Rollup before) {
        Rollup after = take(sheetId);
        after.people().forEach((id, figures) -> {
            if (!figures.sameAs(before.people().get(id))) {
                audit.recordFiguresOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + figures.name()
                        + " claimed=" + figures.claimed().toPlainString() + " due-total=" + shown(figures.dueTotal())
                        + " overdue-total=" + figures.overdueTotal().toPlainString());
            }
        });
        if (!same(after.claimedTotal(), before.claimedTotal()) || !same(after.dueTotal(), before.dueTotal())
                || !same(after.overdueTotal(), before.overdueTotal())) {
            audit.recordFiguresOnCommit("TOTALS sheet=" + sheetId + " by=" + user
                    + " claimed-total=" + after.claimedTotal().toPlainString()
                    + " due-total=" + after.dueTotal().toPlainString()
                    + " overdue-total=" + after.overdueTotal().toPlainString());
        }
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold != null) {
            after.people().forEach((id, figures) -> {
                Figures was = before.people().get(id);
                if (above(figures.dueTotal(), threshold) && !(was != null && above(was.dueTotal(), threshold))) {
                    audit.recordFiguresOnCommit("ALERT sheet=" + sheetId + " by=" + user + " name=" + figures.name()
                            + " due-total=" + figures.dueTotal().toPlainString());
                }
            });
        }
    }

    /** Whether a due-total is above a threshold; n/a never is. */
    private static boolean above(BigDecimal due, BigDecimal threshold) {
        return due != null && due.compareTo(threshold) > 0;
    }

    /**
     * Whether a claim is overdue: OPEN or PENDING with its due-by (plus its grade's grace, see
     * {@link Sheet#lateFrom}) before the sheet's today, and not deferred on it.
     */
    private static boolean overdue(Claim claim, Sheet sheet, String grade) {
        if (sheet == null) {
            return false;
        }
        LocalDate today = sheet.getToday();
        return claim.getStatus().isOpen() && sheet.lateFrom(claim.getDueBy(), grade, claim.getDate()).isBefore(today)
                && !claim.isDeferredOn(today);
    }

    /** Whether two figures are the same amount, or both n/a. */
    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** A due-total as shown: two decimals, or n/a. */
    public static String shown(BigDecimal due) {
        return due != null ? due.toPlainString() : NO_RATE;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
