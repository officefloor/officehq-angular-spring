package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.claim.Rounding;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed, due-total and overdue-total,
 * and the sheet's claimed-total, due-total and overdue-total. A change takes a {@link #snapshot} before it and passes it
 * to {@link #recordChanges} after its own audit lines, which then records a ROLLUP line for each
 * person whose figures moved (people in list order; a person new to the sheet
 * counts as moved) and a TOTALS line when any of the sheet's totals moved, then an ALERT line for
 * each person whose due-total went from at or below the sheet's alert threshold (or n/a, or new) to
 * above it.
 */
@Service
public class RollupService {

    /** A person's figures: claimed, due-total held to the sheet's cap (null: n/a), and
     *  overdue-total (the dues their overdue counting claims show, n/a ones skipped, not capped). */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal, BigDecimal overdueTotal) {
        boolean sameAs(Figures other) {
            return other != null && claimed.compareTo(other.claimed) == 0
                    && Objects.equals(text(dueTotal), text(other.dueTotal))
                    && overdueTotal.compareTo(other.overdueTotal) == 0;
        }
    }

    /** The sheet's figures at one moment: each person's, by person id in list order. */
    public record Snapshot(Map<Long, Figures> people) {
        BigDecimal claimedTotal() {
            return people.values().stream().map(Figures::claimed).reduce(zero(), BigDecimal::add);
        }

        /** Everyone's due-total added up, skipping n/a, trimmed. */
        BigDecimal dueTotal() {
            return trimmedSum(people.values().stream().map(Figures::dueTotal).filter(Objects::nonNull).toList());
        }

        /** Everyone's overdue-total added up, trimmed. */
        BigDecimal overdueTotal() {
            return trimmedSum(people.values().stream().map(Figures::overdueTotal).toList());
        }
    }

    /** The figures added up, leaving out the single highest and the single lowest when there are
     *  three or more. */
    static BigDecimal trimmedSum(List<BigDecimal> figures) {
        BigDecimal sum = figures.stream().reduce(zero(), BigDecimal::add);
        if (figures.size() < 3) {
            return sum;
        }
        BigDecimal max = figures.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = figures.stream().min(BigDecimal::compareTo).orElseThrow();
        return sum.subtract(max).subtract(min);
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimLogRepository log;
    private final RateChain rateChain;
    private final SheetRepository sheets;
    private final Audit audit;

    public RollupService(PersonRepository people, ClaimRepository claims, ClaimLogRepository log,
            RateChain rateChain, SheetRepository sheets, Audit audit) {
        this.rateChain = rateChain;
        this.people = people;
        this.claims = claims;
        this.log = log;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's figures now, as the store (and this transaction) holds them. */
    public Snapshot snapshot(String sheetId) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElseGet(LocalDate::now);
        Function<LocalDate, BigDecimal> floor = floorOf(sheet);
        BigDecimal cap = sheet.map(Sheet::getCap).orElse(null);
        LateCharge late = LateCharge.of(sheet);
        List<Rate> sheetRates = rateChain.effective(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> held = heldPeople(sheetPeople);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        Map<Long, List<Claim>> counted = new LinkedHashMap<>();
        for (Claim c : sheetClaims) {
            if (counts(c, held, today)) {
                counted.computeIfAbsent(c.getPersonId(), id -> new ArrayList<>()).add(c);
            }
        }
        Map<Long, Figures> out = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            List<Claim> theirs = counted.getOrDefault(p.getId(), List.of());
            BigDecimal claimed = theirs.stream().filter(c -> !isCorrection(c))
                    .map(Claim::getAmount).reduce(zero(), BigDecimal::add);
            BigDecimal overdue = theirs.stream()
                    .filter(c -> c.getStatus().isOpen() && late.isPast(c, p))
                    .map(c -> shownDueOf(c, p, sheetRates, floor, late, rounding))
                    .filter(Objects::nonNull)
                    .reduce(zero(), BigDecimal::add);
            out.put(p.getId(), new Figures(p.getName(), claimed,
                    Sheet.capped(dueTotalOf(theirs, p, sheetRates, today, floor, late, rounding), cap), overdue));
        }
        return new Snapshot(out);
    }

    /** Figures added up over sheets: how many people (retired ones too), and the due-totals and
     *  overdue-totals each sheet shows. */
    public record Consolidated(int people, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    /** The sheet's own figures as it is now: how many people (retired ones too), and the
     *  due-total and overdue-total it shows. */
    public Consolidated own(String sheetId) {
        Snapshot figures = snapshot(sheetId);
        return new Consolidated(figures.people().size(), figures.dueTotal(), figures.overdueTotal());
    }

    /** The figures of the sheet's descendants (its children, theirs, and so on), each sheet's as
     *  it is now, added up; all zero for a sheet with no children. */
    public Consolidated descendants(String sheetId) {
        int count = 0;
        BigDecimal due = zero();
        BigDecimal overdue = zero();
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(sheetId));
        while (!todo.isEmpty()) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(todo.poll())) {
                if (!seen.add(child.getId())) {
                    continue;
                }
                todo.add(child.getId());
                Snapshot figures = snapshot(child.getId());
                count += figures.people().size();
                due = due.add(figures.dueTotal());
                overdue = overdue.add(figures.overdueTotal());
            }
        }
        return new Consolidated(count, due, overdue);
    }

    /** Each person's due-total for the period from {@code from} to {@code to}, inclusive, by person
     *  id, for everyone on the sheet: the dues (n/a ones skipped) their claims due inside it show
     *  now, counting each by the status its log gives it at the end of {@code to} (OPEN and SETTLED
     *  ones count; one added after then does not; OPEN ones within a hold of their person, unbound,
     *  or deferred, left out). */
    public Map<Long, BigDecimal> dueByPersonBetween(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElseGet(LocalDate::now);
        Function<LocalDate, BigDecimal> floor = floorOf(sheet);
        LateCharge late = LateCharge.of(sheet);
        List<Rate> sheetRates = rateChain.effective(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        Map<Long, Person> held = heldPeople(sheetPeople);
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            out.put(p.getId(), zero());
        }
        Map<Long, ClaimStatus> statusAtEnd = statusesOn(sheetId, to);
        sheetClaims.stream()
                .filter(c -> countsAt(c, statusAtEnd.containsKey(c.getId()) ? statusAtEnd.get(c.getId()) : c.getStatus(),
                        held, today))
                .filter(c -> !c.getDueBy().isBefore(from) && !c.getDueBy().isAfter(to))
                .filter(c -> byId.containsKey(c.getPersonId()))
                .forEach(c -> {
                    BigDecimal due = shownDueOf(c, byId.get(c.getPersonId()), sheetRates, floor, late, rounding);
                    if (due != null) {
                        out.merge(c.getPersonId(), due, BigDecimal::add);
                    }
                });
        return out;
    }

    /** A statement's figures for a person: their claims that count, each with the figure a statement
     *  binds it to (null: n/a), and their due-total held to the cap (null: n/a). */
    public record Stated(Map<Claim, BigDecimal> counted, BigDecimal dueTotal) {
    }

    /** The statement of a person on a sheet that is not stored (one rebuilt as it stood at the end of
     *  a date, its today that date): counting their claims among {@code sheetClaims}, by the rates,
     *  floor, late charge and cap of the sheet and the person's holds and retirement, as the live
     *  totals do. */
    public static Stated statedOn(Sheet sheet, Person person, Long personId, List<Claim> sheetClaims,
            List<Rate> sheetRates) {
        Optional<Sheet> held = Optional.of(sheet);
        LocalDate today = sheet.getToday();
        Function<LocalDate, BigDecimal> floor = floorOf(held);
        LateCharge late = LateCharge.of(held);
        Function<Claim, RoundingMode> rounding = roundingOf(held, sheetClaims);
        Map<Claim, BigDecimal> counted = new LinkedHashMap<>();
        sheetClaims.stream()
                .filter(c -> c.getPersonId().equals(personId) && countsFor(c, person, today))
                .forEach(c -> counted.put(c, c.getSettledDue() != null ? c.getSettledDue()
                        : figureOf(c, person, sheetRates, rounding.apply(c))));
        BigDecimal dueTotal = dueTotalOf(List.copyOf(counted.keySet()), person, sheetRates, today, floor, late, rounding);
        return new Stated(counted, Sheet.capped(dueTotal, sheet.getCap()));
    }

    /** Keep on each person a snapshot line for {@code day} (the day being left) with their
     *  due-total in {@code before}, and record, once the transaction commits, a SNAPSHOT line for
     *  each (in list order). */
    public void recordSnapshot(String sheetId, String user, LocalDate day, Snapshot before) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, p -> p));
        before.people().forEach((id, p) -> {
            Person person = byId.get(id);
            if (person != null) {
                person.addSnapshot(day, p.dueTotal() == null ? null : p.dueTotal().setScale(2));
            }
            audit.recordOnCommit("SNAPSHOT sheet=" + sheetId + " by=" + user
                    + " name=" + p.name() + " due-total=" + text(p.dueTotal()));
        });
        people.flush();
    }

    /** Record, once the transaction commits, a ROLLUP line for each person whose figures moved
     *  since {@code before}, then a TOTALS line when the sheet's totals moved. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        recordChanges(sheetId, user, before, false);
    }

    private void recordChanges(String sheetId, String user, Snapshot before, boolean totals) {
        Snapshot after = snapshot(sheetId);
        after.people().forEach((id, now) -> {
            if (!now.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + now.name()
                        + " claimed=" + text(now.claimed()) + " due-total=" + text(now.dueTotal()) + " overdue-total=" + text(now.overdueTotal()));
            }
        });
        if (totals || after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0
                || after.overdueTotal().compareTo(before.overdueTotal()) != 0) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total="
                    + text(after.claimedTotal()) + " due-total=" + text(after.dueTotal())
                    + " overdue-total=" + text(after.overdueTotal()));
        }
        BigDecimal threshold = sheets.findById(sheetId).map(Sheet::getAlertThreshold).orElse(null);
        if (threshold != null) {
            after.people().forEach((id, now) -> {
                Figures was = before.people().get(id);
                if (above(now.dueTotal(), threshold) && (was == null || !above(was.dueTotal(), threshold))) {
                    audit.recordOnCommit("ALERT sheet=" + sheetId + " by=" + user + " name=" + now.name()
                            + " due-total=" + text(now.dueTotal()));
                }
            });
        }
    }

    /** Whether a due-total (null: n/a) is above the alert threshold. */
    private static boolean above(BigDecimal dueTotal, BigDecimal threshold) {
        return dueTotal != null && dueTotal.compareTo(threshold) > 0;
    }

    /** Record, once the transaction commits, a ROLLUP line for every person (in list order), then
     *  the sheet's TOTALS line, as the sheet's figures are now. */
    public void recordAll(String sheetId, String user) {
        recordChanges(sheetId, user, new Snapshot(Map.of()), true);
    }

    /** Whether a claim counts toward claimed and due totals: only OPEN (or PENDING) and SETTLED ones
     *  do, but not an open one dated within a hold of its person (by id in {@code held}) unless bound
     *  by a statement, nor (so unbound) any open one of a person whose retirement date the sheet's
     *  today has reached, nor one deferred on the sheet's today. */
    private static boolean counts(Claim c, Map<Long, Person> held, LocalDate today) {
        return countsFor(c, held.get(c.getPersonId()), today);
    }

    /** Whether a claim of the person (null: one with neither a hold nor a retirement) counts, as
     *  {@link #counts} has it. */
    private static boolean countsFor(Claim c, Person person, LocalDate today) {
        if (c.getStatus() == ClaimStatus.SETTLED) {
            return true;
        }
        if (!c.getStatus().isOpen() || c.isDeferredOn(today)) {
            return false;
        }
        return c.isBound() || !leftOut(person, c, today);
    }

    /** Whether a claim counts toward a period's due-total, taken in {@code status} (the one it had
     *  at the period's end; null when it was not yet added then): as {@link #counts} has it. */
    private static boolean countsAt(Claim c, ClaimStatus status, Map<Long, Person> held, LocalDate today) {
        if (status == null) {
            return false;
        }
        if (status == ClaimStatus.SETTLED) {
            return true;
        }
        if (!status.isOpen() || c.isDeferredOn(today)) {
            return false;
        }
        // a hold or a reached retirement leaves out a claim still open now, as the live due-total does
        return !c.getStatus().isOpen() || c.isBound() || !leftOut(held.get(c.getPersonId()), c, today);
    }

    /** The status each of the sheet's claims had at the end of {@code on}, by claim id, as their
     *  status logs record it (null for a claim not yet added then); a claim with no log at all is
     *  left out, taken in its status now. */
    private Map<Long, ClaimStatus> statusesOn(String sheetId, LocalDate on) {
        Map<Long, ClaimStatus> out = new LinkedHashMap<>();
        for (ClaimLog line : log.findBySheetIdOrderByIdAsc(sheetId)) {
            ClaimStatus before = out.get(line.getClaimId());
            out.put(line.getClaimId(), line.getOn().isAfter(on) ? before : line.getStatus());
        }
        return out;
    }

    /** Whether an unbound open claim of the person (null: one with neither a hold nor a retirement)
     *  is left out of the live totals: dated within a hold of theirs, or their retirement date
     *  reached on the sheet's today. */
    private static boolean leftOut(Person person, Claim c, LocalDate today) {
        return person != null && (person.heldOn(c.getDate()) || person.retiredOn(today));
    }

    /** The people with a hold or a retirement date, by id. */
    private static Map<Long, Person> heldPeople(List<Person> sheetPeople) {
        return sheetPeople.stream().filter(p -> p.isHeld() || p.isRetired()).collect(Collectors.toMap(Person::getId, p -> p));
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade in force on the sheet's today has no rate then, unless a claim
     *  shows a due (a fixed or bound one, or one at the rate of the grade they had on its date). */
    private static BigDecimal dueTotalOf(List<Claim> counted, Person person, List<Rate> sheetRates, LocalDate today,
            Function<LocalDate, BigDecimal> floor, LateCharge late, Function<Claim, RoundingMode> rounding) {
        List<BigDecimal> dues = counted.stream()
                .map(c -> shownDueOf(c, person, sheetRates, floor, late, rounding))
                .filter(Objects::nonNull)
                .toList();
        if (inForce(person, sheetRates, today).isEmpty() && dues.isEmpty()) {
            return null;
        }
        return dues.stream().reduce(zero(), BigDecimal::add);
    }

    /** The due a claim shows: the due fixed when it was settled, else the one a statement bound it
     *  to (null: n/a), else its amount times the rate in force on its date of its person's grade
     *  then, rounded as its rounding policy has it; an unsettled one with its late charge on top, then raised to the
     *  sheet's floor in force on its date (a correction's never is). */
    private static BigDecimal shownDueOf(Claim c, Person person, List<Rate> sheetRates, Function<LocalDate, BigDecimal> floor,
            LateCharge late, Function<Claim, RoundingMode> rounding) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        RoundingMode mode = rounding.apply(c);
        BigDecimal due = late.addTo(c, person, figureOf(c, person, sheetRates, mode), mode);
        return due == null || c.isCorrection() ? due : due.max(floor.apply(c.getDate()));
    }

    /** A claim's figure before any late charge or floor: the one a statement bound it to (null: n/a),
     *  else its rate figure. */
    private static BigDecimal figureOf(Claim c, Person person, List<Rate> sheetRates, RoundingMode mode) {
        if (c.isBound()) {
            return c.getStatedDue();
        }
        return inForce(person, sheetRates, c.getDate())
                .map(r -> c.getAmount().multiply(r.getPct()).divide(BigDecimal.valueOf(100), 2, mode))
                .orElse(null);
    }

    /** How each claim's live figures round: by the sheet's policy date against its date, or for a
     *  correction the date of the claim (of the same person) it corrects. */
    private static Function<Claim, RoundingMode> roundingOf(Optional<Sheet> sheet, List<Claim> sheetClaims) {
        Rounding rounding = Rounding.of(sheet);
        Map<String, LocalDate> numbered = new LinkedHashMap<>();
        for (Claim c : sheetClaims) {
            if (c.getNumber() != null) {
                numbered.putIfAbsent(c.getPersonId() + "/" + c.getNumber(), c.getDate());
            }
        }
        return c -> rounding.on(c.getCorrects() == null ? c.getDate()
                : numbered.getOrDefault(c.getPersonId() + "/" + c.getCorrects(), c.getDate()));
    }

    /** The rate in force on a date for the person's grade in force then; none when that one's
     *  to-date has passed by then. */
    private static Optional<Rate> inForce(Person person, List<Rate> sheetRates, LocalDate on) {
        String grade = person.gradeOn(on);
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade) && r.appliesOn(on))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.runsThrough(on));
    }

    /** The sheet's floor in force on each date. */
    private static Function<LocalDate, BigDecimal> floorOf(Optional<Sheet> sheet) {
        return on -> sheet.map(s -> s.floorOn(on)).orElse(Sheet.DEFAULT_FLOOR);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    /** A figure as the screen shows it: two decimals, or n/a. */
    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2).toPlainString();
    }

    /** Whether a claim corrects another: claimed leaves such claims out. */
    private static boolean isCorrection(Claim c) {
        return c.getCorrects() != null && !c.getCorrects().isBlank();
    }
}
