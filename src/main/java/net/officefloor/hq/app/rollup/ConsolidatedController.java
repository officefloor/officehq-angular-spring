package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.SheetState;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateChains;
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
     *  up: how many people, and each one's due-total and overdue-total as that sheet shows it, with two decimals;
     *  per grade, the due-totals of the people not retired on it on their sheet's today (none counting as 0), with
     *  two decimals; and per claim status, how many claims are in it. */
    public record DescendantFigures(int peopleCount, String dueTotal, String overdueTotal,
            Map<String, String> dueByGrade, Map<String, Integer> countByStatus) {
    }

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final SheetHistory history;
    private final RateChains rates;
    private final PersonRepository people;
    private final ClaimRepository claims;

    public ConsolidatedController(SheetRepository sheets, Rollup rollup, SheetHistory history, RateChains rates,
            PersonRepository people, ClaimRepository claims) {
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
     *  standing in for its today; one with no change made on it by then has no one on it. */
    @GetMapping("/figures")
    @Transactional(readOnly = true)
    public DescendantFigures figures(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestParam(defaultValue = "false") boolean self,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        Map<String, BigDecimal> dueByGrade = new LinkedHashMap<>();
        Map<String, Integer> countByStatus = new LinkedHashMap<>();
        List<String> counted = new ArrayList<>(sheets.descendantsOf(sheetId));
        if (self) {
            counted.add(0, sheetId);
        }
        for (String descendant : counted) {
            Counted one = asOf == null ? live(descendant) : countedAsOf(descendant, asOf);
            if (one == null) {
                continue;
            }
            Rollup.Figures figures = one.figures();
            people += figures.people().size();
            due = due.add(new BigDecimal(figures.dueTotal()));
            overdue = overdue.add(new BigDecimal(figures.overdueTotal()));
            LocalDate today = one.sheet() == null ? null : one.sheet().getToday();
            for (Person person : one.people()) {
                Rollup.PersonFigures own = figures.people().get(person.getId());
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
        return new DescendantFigures(people, due.toPlainString(), overdue.toPlainString(), dueByGradeShown,
                countByStatus);
    }

    /** A sheet as the store holds it now. */
    private Counted live(String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        return new Counted(sheet, rollup.of(sheet, rates.of(sheetId), sheetPeople, sheetClaims), sheetPeople,
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
        return new Counted(sheet, rollup.of(sheet, history.ratesAsOf(sheetId, asOf), sheetPeople, sheetClaims),
                sheetPeople, sheetClaims);
    }
}
