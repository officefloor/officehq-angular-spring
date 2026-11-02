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
import net.officefloor.hq.app.asof.AsOfTree;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The figures of a sheet's descendants (the sheets naming it as their parent, theirs and so on),
 * added up: how many people they have, and each one's due-total and overdue-total as that sheet
 * shows them, and the sheet's own figures. Read as of a date, every sheet of the tree is as it stood at
 * the end of that date, with that date as its today. Per grade, the due-totals of the people on it on
 * their sheet's today (retired people left out, an n/a due-total no figure) added up, and per status the
 * count of claims in it. The screen adds the sheet's own figures, as shown,
 * to make the consolidated ones; for that each descendant person's figures are given too, with the keys
 * their name and aliases are compared by, so the screen can trim over the whole tree and count each
 * person once.
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/descendants")
public class ConsolidatedController {

    /** The statuses the descendants' figures are given per (and the grades: the sheet's, then any other a descendant has). */
    private static final List<String> STATUSES = List.of("DRAFT", "OPEN", "PENDING", "SETTLED", "VOID", "REVIEW");

    /**
     * The descendants' figures added up, then the sheet's own; the totals have two decimals. Per grade the
     * descendants' due-totals and per status their claim counts are added up too, and how many of their
     * claims are overdue.
     */
    public record DescendantsView(int sheets, int people, String dueTotal, String overdueTotal, int ownPeople,
            String ownDueTotal, String ownOverdueTotal, Map<String, String> gradeDueTotals,
            Map<String, Integer> statusCounts, List<PersonFigures> personFigures, int overdueCount) {}

    /**
     * A descendant person's figures as their sheet shows them: the keys of their name and aliases (as names
     * are compared), their due-total (null when n/a) and overdue-total.
     */
    public record PersonFigures(List<String> keys, String dueTotal, String overdueTotal) {}

    private final SheetParentRepository parents;
    private final RollupService rollup;
    private final PersonRepository persons;
    private final ClaimRepository claims;
    private final SheetRepository sheetRepository;

    public ConsolidatedController(SheetParentRepository parents, RollupService rollup, PersonRepository people,
            ClaimRepository claims, SheetRepository sheetRepository) {
        this.parents = parents;
        this.rollup = rollup;
        this.persons = people;
        this.claims = claims;
        this.sheetRepository = sheetRepository;
    }

    @GetMapping("/figures")
    @AsOfTree
    @Transactional(readOnly = true)
    public DescendantsView figures(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        int sheets = 0;
        int people = 0;
        int overdueCount = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        Map<String, BigDecimal> gradeDues = new LinkedHashMap<>();
        for (String grade : sheetRepository.findById(sheetId).map(Sheet::getGrades).orElse(Sheet.STANDING_GRADES)) {
            gradeDues.put(grade, BigDecimal.ZERO.setScale(2));
        }
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        for (String status : STATUSES) statusCounts.put(status, 0);
        List<PersonFigures> personFigures = new ArrayList<>();
        for (String descendant : parents.descendantsOf(sheetId)) {
            RollupService.Snapshot figures = rollup.current(descendant);
            sheets++;
            people += figures.people().size();
            due = due.add(figures.dueTotal());
            overdue = overdue.add(figures.overdueTotal());
            for (RollupService.Figures theirs : figures.people().values()) overdueCount += theirs.overdueCount();
            LocalDate today = sheetRepository.findById(descendant).map(Sheet::getToday).orElse(null);
            for (Person person : persons.findBySheetIdOrderByPositionAscIdAsc(descendant)) {
                RollupService.Figures theirs = figures.people().get(person.getId());
                if (theirs != null) {
                    personFigures.add(new PersonFigures(person.namesAndAliases().stream().map(Person::keyOf).toList(),
                            theirs.dueTotal() == null ? null : theirs.dueTotal().setScale(2).toPlainString(),
                            theirs.overdueTotal().setScale(2).toPlainString()));
                }
                if (person.isRetired()) continue;
                if (theirs == null || theirs.dueTotal() == null) continue;
                String grade = today == null ? person.getGrade() : person.gradeOn(today);
                gradeDues.merge(grade, theirs.dueTotal(), BigDecimal::add);
            }
            for (Claim claim : claims.findBySheetIdOrderByIdDesc(descendant)) {
                statusCounts.computeIfPresent(claim.getStatus().name(), (st, count) -> count + 1);
            }
        }
        Map<String, String> gradeDueTotals = new LinkedHashMap<>();
        gradeDues.forEach((grade, total) -> gradeDueTotals.put(grade, total.setScale(2).toPlainString()));
        RollupService.Snapshot own = rollup.current(sheetId);
        return new DescendantsView(sheets, people, due.toPlainString(), overdue.toPlainString(), own.people().size(),
                own.dueTotal().setScale(2).toPlainString(), own.overdueTotal().setScale(2).toPlainString(),
                gradeDueTotals, statusCounts, personFigures, overdueCount);
    }
}
