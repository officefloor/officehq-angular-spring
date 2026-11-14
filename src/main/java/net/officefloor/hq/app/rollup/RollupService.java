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
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElseGet(LocalDate::now);
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
            List<Rate> theirRates = sheetRates.stream().filter(r -> r.getGrade().equals(p.getGrade())).toList();
            List<Claim> theirs = counted.getOrDefault(p.getId(), List.of());
            BigDecimal claimed = theirs.stream().map(Claim::getAmount).reduce(zero(), BigDecimal::add);
            out.put(p.getId(), new Figures(p.getName(), claimed, dueTotalOf(theirs, theirRates, today)));
        }
        return new Snapshot(out);
    }

    /** The due-total of the period from {@code from} to {@code to}, inclusive: the dues (n/a ones
     *  skipped) its OPEN and SETTLED claims dated inside it show now (a held person's OPEN ones left out). */
    public BigDecimal dueTotalBetween(String sheetId, LocalDate from, LocalDate to) {
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAsc(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        Set<Long> held = heldIds(sheetPeople);
        Map<Long, List<Rate>> ratesByPerson = new LinkedHashMap<>();
        for (Person p : sheetPeople) {
            ratesByPerson.put(p.getId(), sheetRates.stream().filter(r -> r.getGrade().equals(p.getGrade())).toList());
        }
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> counts(c, held))
                .filter(c -> !c.getDate().isBefore(from) && !c.getDate().isAfter(to))
                .map(c -> shownDueOf(c, ratesByPerson.getOrDefault(c.getPersonId(), List.of())))
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
     *  null (n/a) when their grade has no rate in force on the sheet's today, unless a claim keeps
     *  a fixed or bound due. */
    private static BigDecimal dueTotalOf(List<Claim> counted, List<Rate> theirRates, LocalDate today) {
        boolean kept = counted.stream()
                .anyMatch(c -> c.getSettledDue() != null || (c.isStated() && c.getStatedDue() != null));
        if (inForce(theirRates, today).isEmpty() && !kept) {
            return null;
        }
        return counted.stream()
                .map(c -> shownDueOf(c, theirRates))
                .filter(Objects::nonNull)
                .reduce(zero(), BigDecimal::add);
    }

    /** The due a claim shows: the due fixed when it was settled, else the one a statement bound it
     *  to (null: n/a), else its amount times the rate in force on its date, rounded half to even. */
    private static BigDecimal shownDueOf(Claim c, List<Rate> theirRates) {
        if (c.getSettledDue() != null) {
            return c.getSettledDue();
        }
        if (c.isStated()) {
            return c.getStatedDue();
        }
        return inForce(theirRates, c.getDate())
                .map(r -> c.getAmount().multiply(r.getPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN))
                .orElse(null);
    }

    private static Optional<Rate> inForce(List<Rate> theirRates, LocalDate on) {
        return theirRates.stream()
                .filter(r -> r.appliesOn(on))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    /** A figure as the screen shows it: two decimals, or n/a. */
    private static String text(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2).toPlainString();
    }
}
