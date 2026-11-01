package net.officefloor.hq.app.rollup;

import net.officefloor.hq.app.pool.PoolService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.SheetState;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateChains;
import net.officefloor.hq.app.sheet.Preview;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's descendants' figures, which the screen adds to the sheet's own to show them consolidated. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The figures of the sheet's descendants (its children, their children and so on; not the sheet itself) added
     *  up: how many people, each person counted once however many sheets they are on (people whose names or aliases
     *  match as names are compared being one); the due-totals and overdue-totals of all their people, trimmed once of
     *  the single highest and lowest person's figure when three or more have one, with two decimals; each person's
     *  names and figures, so the screen can count and trim its own people with them; per grade, the due-totals of the people not retired on it on their sheet's today (none counting as 0), with
     *  two decimals; per claim status, how many claims are in it; and how many claims that count are overdue on
     *  their sheet's today. */
    public record DescendantFigures(int peopleCount, String dueTotal, String overdueTotal,
            Map<String, String> dueByGrade, Map<String, Integer> countByStatus, List<PersonFigure> people,
            int overdueCount) {
    }

    /** A person of the tree: their name and aliases, their due-total (null when they have none) and overdue-total as
     *  their sheet shows them, with two decimals. */
    public record PersonFigure(List<String> names, String dueTotal, String overdueTotal) {
    }

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final SheetHistory history;
    private final RateChains rates;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final PoolService pools;

    public ConsolidatedController(SheetRepository sheets, Rollup rollup, SheetHistory history, RateChains rates,
            PersonRepository people, ClaimRepository claims, PoolService pools) {
        this.pools = pools;
        this.sheets = sheets;
        this.rollup = rollup;
        this.history = history;
        this.rates = rates;
        this.people = people;
        this.claims = claims;
    }

    /** A sheet of the tree as counted: its figures, and the people and claims they were worked out from. */
    private record Counted(Sheet sheet, Rollup.Figures figures, List<Person> people, List<Claim> claims) {
    }

    /** With {@code self}, the sheet's own figures are added in too. While the screen shows the sheet as of a date,
     *  each sheet of the tree (as it names its parent now) counts as it stood at the end of that date, that date
     *  standing in for its today; one with no change made on it by then has no one on it. While the screen previews a
     *  date, each sheet of the tree counts over its current records with that date standing in for its today. */
    @GetMapping("/figures")
    @Transactional(readOnly = true)
    public DescendantFigures figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(defaultValue = "false") boolean self,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestHeader(value = Preview.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate preview) {
        List<PersonFigure> treePeople = new ArrayList<>();
        Map<String, BigDecimal> dueByGrade = new LinkedHashMap<>();
        Map<String, Integer> countByStatus = new LinkedHashMap<>();
        int overdueCount = 0;
        List<String> counted = new ArrayList<>(sheets.descendantsOf(sheetId));
        if (self) {
            counted.add(0, sheetId);
        }
        for (String descendant : counted) {
            Counted one = asOf == null ? live(descendant, preview) : countedAsOf(descendant, asOf);
            if (one == null) {
                continue;
            }
            Rollup.Figures figures = one.figures();
            LocalDate today = one.sheet() == null ? null : one.sheet().getToday();
            for (Person person : one.people()) {
                if (today != null) {
                    overdueCount += (int) one.claims().stream()
                            .filter(c -> c.getPersonId().equals(person.getId()) && person.counts(c, today)
                                    && !c.isDeferredOn(today)
                                    && ClaimService.isOverdue(c, one.sheet(), person.gradeOn(c.getDate())))
                            .count();
                }
                Rollup.PersonFigures own = figures.people().get(person.getId());
                if (own != null) {
                    List<String> names = new ArrayList<>();
                    names.add(person.getName());
                    names.addAll(person.getAliases());
                    treePeople.add(new PersonFigure(names, "n/a".equals(own.dueTotal()) ? null : own.dueTotal(),
                            own.overdueTotal()));
                }
                if (person.isRetired() || own == null || "n/a".equals(own.dueTotal())) {
                    continue;
                }
                dueByGrade.merge(person.gradeOn(today), new BigDecimal(own.dueTotal()), BigDecimal::add);
            }
            for (Claim claim : one.claims()) {
                countByStatus.merge(claim.getStatus().name(), 1, Integer::sum);
            }
        }
        Map<String, String> dueByGradeShown = new LinkedHashMap<>();
        dueByGrade.forEach((grade, total) -> dueByGradeShown.put(grade, total.setScale(2).toPlainString()));
        BigDecimal due = Rollup.trimmedSum(treePeople.stream().map(PersonFigure::dueTotal).filter(Objects::nonNull)
                .map(BigDecimal::new).toList());
        BigDecimal overdue = Rollup.trimmedSum(treePeople.stream().map(p -> new BigDecimal(p.overdueTotal())).toList());
        return new DescendantFigures(distinctPeople(treePeople), due.toPlainString(), overdue.toPlainString(),
                dueByGradeShown, countByStatus, treePeople, overdueCount);
    }

    /** How many distinct people these are: those sharing a name or alias, as names are compared (trimmed, case
     *  ignored), directly or through others, being one. */
    static int distinctPeople(List<PersonFigure> treePeople) {
        int[] parent = new int[treePeople.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Map<String, Integer> firstWithName = new HashMap<>();
        int distinct = parent.length;
        for (int i = 0; i < parent.length; i++) {
            for (String name : treePeople.get(i).names()) {
                Integer other = firstWithName.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), i);
                if (other != null) {
                    int a = root(parent, i), b = root(parent, other);
                    if (a != b) {
                        parent[a] = b;
                        distinct--;
                    }
                }
            }
        }
        return distinct;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            i = parent[i] = parent[parent[i]];
        }
        return i;
    }

    /** A sheet as the store holds it now; with a preview date (else null), that date standing in for its today. */
    private Counted live(String sheetId, LocalDate preview) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        return new Counted(Preview.of(sheet, preview),
                rollup.previewed(sheet, rates.of(sheetId), sheetPeople, sheetClaims, preview), sheetPeople,
                sheetClaims);
    }

    /** A sheet's figures as it stood at the end of a date, that date as its today; none when no change was made on it
     *  by then. */
    private Counted countedAsOf(String sheetId, LocalDate asOf) {
        Sheet live = sheets.findById(sheetId).orElse(null);
        SheetState then = live == null ? null : history.asOf(sheetId, asOf).orElse(null);
        if (then == null) {
            return null;
        }
        Sheet sheet = then.rebuildSheet(live, asOf);
        List<Person> sheetPeople = then.rebuildPeople(sheetId);
        List<Claim> sheetClaims = then.rebuildClaims(sheetId);
        return new Counted(sheet, rollup.of(sheet, history.ratesAsOf(sheetId, asOf), sheetPeople, sheetClaims,
                        pools.cutTotals(sheetId, asOf)),
                sheetPeople, sheetClaims);
    }
}
