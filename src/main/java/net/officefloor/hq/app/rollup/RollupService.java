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
import java.util.function.Function;
import java.util.stream.Collectors;
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
        Map<Long, Person> held = heldPeople(sheetPeople);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        Map<Long, List<Claim>> counted = new LinkedHashMap<>();
        for (Claim c : sheetClaims) {
            if (counts(c, held)) {
                counted.computeIfAbsent(c.getPersonId(), id -> new ArrayList<>()).add(c);
            }
        }
        Map<Long, Figures> out = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            List<Claim> theirs = counted.getOrDefault(p.getId(), List.of());
            BigDecimal claimed = theirs.stream().map(Claim::getAmount).reduce(zero(), BigDecimal::add);
            out.put(p.getId(), new Figures(p.getName(), claimed, dueTotalOf(theirs, p, sheetRates, today, floor, late, rounding)));
        }
        return new Snapshot(out);
    }

    /** The due-total of the period from {@code from} to {@code to}, inclusive: the dues (n/a ones
     *  skipped) its OPEN and SETTLED claims dated inside it show now (OPEN ones within a hold of their person, unbound, left out). */
    public BigDecimal dueTotalBetween(String sheetId, LocalDate from, LocalDate to) {
        Optional<Sheet> sheet = sheets.findById(sheetId);
        BigDecimal floor = floorOf(sheet);
        LateCharge late = LateCharge.of(sheet);
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        Map<Long, Person> held = heldPeople(sheetPeople);
        Map<Long, Person> byId = sheetPeople.stream().collect(Collectors.toMap(Person::getId, p -> p));
        List<Claim> sheetClaims = claims.findBySheetIdOrderByIdDesc(sheetId);
        Function<Claim, RoundingMode> rounding = roundingOf(sheet, sheetClaims);
        return sheetClaims.stream()
                .filter(c -> counts(c, held))
                .filter(c -> !c.getDate().isBefore(from) && !c.getDate().isAfter(to))
                .filter(c -> byId.containsKey(c.getPersonId()))
                .map(c -> shownDueOf(c, byId.get(c.getPersonId()), sheetRates, floor, late, rounding))
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

    /** Whether a claim counts toward claimed and due totals: only OPEN (or PENDING) and SETTLED ones
     *  do, but not an open one dated within a hold of its person (by id in {@code held}) unless bound
     *  by a statement. */
    private static boolean counts(Claim c, Map<Long, Person> held) {
        if (c.getStatus() == ClaimStatus.SETTLED) {
            return true;
        }
        if (!c.getStatus().isOpen()) {
            return false;
        }
        Person person = held.get(c.getPersonId());
        return c.isBound() || person == null || !person.heldOn(c.getDate());
    }

    private static Map<Long, Person> heldPeople(List<Person> sheetPeople) {
        return sheetPeople.stream().filter(Person::isHeld).collect(Collectors.toMap(Person::getId, p -> p));
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade in force on the sheet's today has no rate then, unless a claim
     *  shows a due (a fixed or bound one, or one at the rate of the grade they had on its date). */
    private static BigDecimal dueTotalOf(List<Claim> counted, Person person, List<Rate> sheetRates, LocalDate today,
            BigDecimal floor, LateCharge late, Function<Claim, RoundingMode> rounding) {
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
     *  then, rounded as its rounding policy has it,
     *  raised to the sheet's floor (a correction's never is); an unsettled one with its late charge on top. */
    private static BigDecimal shownDueOf(Claim c, Person person, List<Rate> sheetRates, BigDecimal floor,
            LateCharge late, Function<Claim, RoundingMode> rounding) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        RoundingMode mode = rounding.apply(c);
        return late.addTo(c, figureOf(c, person, sheetRates, floor, mode), mode);
    }

    /** A claim's figure before any late charge: the one a statement bound it to (null: n/a), else
     *  its live due. */
    private static BigDecimal figureOf(Claim c, Person person, List<Rate> sheetRates, BigDecimal floor,
            RoundingMode mode) {
        if (c.isBound()) {
            return c.getStatedDue();
        }
        return inForce(person, sheetRates, c.getDate())
                .map(r -> c.getAmount().multiply(r.getPct()).divide(BigDecimal.valueOf(100), 2, mode))
                .map(due -> c.isCorrection() ? due : due.max(floor))
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
