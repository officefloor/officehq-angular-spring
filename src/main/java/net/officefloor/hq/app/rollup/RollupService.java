package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The sheet's figures as the screen shows them: each person's claimed and due-total, and the
 * sheet's claimed-total and due-total. A change takes a {@link #snapshot} before it and passes it
 * to {@link #recordChanges} after its own audit lines, which then records a ROLLUP line for each
 * person whose figures moved (people in the order they were added; a person new to the sheet
 * counts as moved) and a TOTALS line when the sheet's totals moved.
 */
@Service
public class RollupService {

    /** A person's figures: claimed, and due-total (null: n/a). */
    public record Figures(String name, BigDecimal claimed, BigDecimal dueTotal) {
        boolean sameAs(Figures other) {
            return other != null && claimed.compareTo(other.claimed) == 0
                    && Objects.equals(text(dueTotal), text(other.dueTotal));
        }
    }

    /** The sheet's figures at one moment: each person's, by person id in the order they were added. */
    public record Snapshot(Map<Long, Figures> people) {
        BigDecimal claimedTotal() {
            return people.values().stream().map(Figures::claimed).reduce(zero(), BigDecimal::add);
        }

        /** Everyone's due-total added up, skipping n/a. */
        BigDecimal dueTotal() {
            return people.values().stream().map(Figures::dueTotal).filter(Objects::nonNull)
                    .reduce(zero(), BigDecimal::add);
        }
    }

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

    /** The sheet's figures now, as the store (and this transaction) holds them. */
    public Snapshot snapshot(String sheetId) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        LocalDate today = sheet.map(Sheet::getToday).orElseGet(LocalDate::now);
        BigDecimal floor = floorOf(sheet);
        LateCharge late = LateCharge.of(sheet);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        Set<Long> held = heldIds(sheetPeople);
        Map<Long, List<Claim>> counted = new LinkedHashMap<>();
        for (Claim c : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (counts(c, held)) {
                counted.computeIfAbsent(c.getPersonId(), id -> new ArrayList<>()).add(c);
            }
        }
        Map<Long, Figures> out = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            List<Claim> theirs = counted.getOrDefault(p.getId(), List.of());
            BigDecimal claimed = theirs.stream().map(Claim::getAmount).reduce(zero(), BigDecimal::add);
            out.put(p.getId(), new Figures(p.getName(), claimed, dueTotalOf(theirs, p, sheetRates, today, floor, late)));
        }
        return new Snapshot(out);
    }

    /** The due-total of the period from {@code from} to {@code to}, inclusive: the dues (n/a ones
     *  skipped) its OPEN and SETTLED claims dated inside it show now (a held person's OPEN ones left out). */
    public BigDecimal dueTotalBetween(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        BigDecimal floor = floorOf(sheet);
        LateCharge late = LateCharge.of(sheet);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        Set<Long> held = heldIds(sheetPeople);
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> counts(c, held))
                .filter(c -> !c.getDate().isBefore(from) && !c.getDate().isAfter(to))
                .filter(c -> byId.containsKey(c.getPersonId()))
                .map(c -> shownDueOf(c, byId.get(c.getPersonId()), sheetRates, floor, late))
                .filter(Objects::nonNull)
                .reduce(zero(), BigDecimal::add);
    }

    /** Record, once the transaction commits, a ROLLUP line for each person whose figures moved
     *  since {@code before}, then a TOTALS line when the sheet's totals moved. */
    public void recordChanges(String sheetId, String user, Snapshot before) {
        Snapshot after = snapshot(sheetId);
        after.people().forEach((id, now) -> {
            if (!now.sameAs(before.people().get(id))) {
                audit.recordOnCommit("ROLLUP sheet=" + sheetId + " by=" + user + " person=" + now.name()
                        + " claimed=" + text(now.claimed()) + " due-total=" + text(now.dueTotal()));
            }
        });
        if (after.claimedTotal().compareTo(before.claimedTotal()) != 0
                || after.dueTotal().compareTo(before.dueTotal()) != 0) {
            audit.recordOnCommit("TOTALS sheet=" + sheetId + " by=" + user + " claimed-total="
                    + text(after.claimedTotal()) + " due-total=" + text(after.dueTotal()));
        }
    }

    /** Whether a claim counts toward claimed and due totals: only OPEN and SETTLED ones do, and
     *  of a held person (by id in {@code held}) only SETTLED ones. */
    private static boolean counts(Claim c, Set<Long> held) {
        return c.getStatus() == ClaimStatus.SETTLED
                || (c.getStatus() == ClaimStatus.OPEN && !held.contains(c.getPersonId()));
    }

    private static Set<Long> heldIds(List<Person> sheetPeople) {
        return sheetPeople.stream().filter(Person::isHeld).map(Person::getId).collect(Collectors.toSet());
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade in force on the sheet's today has no rate then, unless a claim
     *  shows a due (a fixed or bound one, or one at the rate of the grade they had on its date). */
    private static BigDecimal dueTotalOf(List<Claim> counted, Person person, List<Rate> sheetRates, LocalDate today,
            BigDecimal floor, LateCharge late) {
        List<BigDecimal> dues = counted.stream()
                .map(c -> shownDueOf(c, person, sheetRates, floor, late))
                .filter(Objects::nonNull)
                .toList();
        if (inForce(person, sheetRates, today).isEmpty() && dues.isEmpty()) {
            return null;
        }
        return dues.stream().reduce(zero(), BigDecimal::add);
    }

    /** The due a claim shows: the due fixed when it was settled, else the one a statement bound it
     *  to (null: n/a), else its amount times the rate in force on its date of its person's grade
     *  then, rounded half to even,
     *  raised to the sheet's floor (a correction's never is); an unsettled one with its late charge on top. */
    private static BigDecimal shownDueOf(Claim c, Person person, List<Rate> sheetRates, BigDecimal floor,
            LateCharge late) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        return late.addTo(c, figureOf(c, person, sheetRates, floor));
    }

    /** A claim's figure before any late charge: the one a statement bound it to (null: n/a), else
     *  its live due. */
    private static BigDecimal figureOf(Claim c, Person person, List<Rate> sheetRates, BigDecimal floor) {
        if (c.isStated()) {
            return c.getStatedDue();
        }
        return inForce(person, sheetRates, c.getDate())
                .map(r -> c.getAmount().multiply(r.getPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .map(due -> c.isCorrection() ? due : due.max(floor))
                .orElse(null);
    }

    /** The rate in force on a date for the person's grade in force then. */
    private static Optional<Rate> inForce(Person person, List<Rate> sheetRates, LocalDate on) {
        String grade = person.gradeOn(on);
        return sheetRates.stream()
                .filter(r -> r.getGrade().equals(grade) && r.appliesOn(on))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static BigDecimal floorOf(Optional<Sheet> sheet) {
        return sheet.map(Sheet::getFloor).orElse(Sheet.DEFAULT_FLOOR);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    /** A figure as the screen shows it: two decimals, or n/a. */
    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2).toPlainString();
    }
}
