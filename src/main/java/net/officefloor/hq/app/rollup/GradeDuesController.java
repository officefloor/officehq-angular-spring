package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.RateChains;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's per-grade due-totals, as its summary shows them, which the screen compares another sheet's with. */
@RestController
@Validated
public class GradeDuesController {

    /** Whether the sheet exists (has ever been loaded), and per grade the due-totals of the people not retired on it
     *  on the sheet's today (none counting as 0), with two decimals; a grade no one counts on is missing. */
    public record GradeDues(boolean exists, Map<String, String> dueByGrade) {
    }

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final RateChains rates;
    private final PersonRepository people;
    private final ClaimRepository claims;

    public GradeDuesController(SheetRepository sheets, Rollup rollup, RateChains rates, PersonRepository people,
            ClaimRepository claims) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.rates = rates;
        this.people = people;
        this.claims = claims;
    }

    /** The sheet's per-grade due-totals as the store holds it now; a sheet never loaded exists not, with none. */
    @GetMapping("/api/sheets/{sheetId}/grade-dues")
    @Transactional(readOnly = true)
    public GradeDues gradeDues(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        if (sheet == null) {
            return new GradeDues(false, Map.of());
        }
        List<Person> sheetPeople = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        Rollup.Figures figures = rollup.of(sheet, rates.of(sheetId), sheetPeople, sheetClaims);
        LocalDate today = sheet.getToday();
        Map<String, BigDecimal> dueByGrade = new LinkedHashMap<>();
        for (Person person : sheetPeople) {
            Rollup.PersonFigures own = figures.people().get(person.getId());
            if (person.isRetired() || own == null || "n/a".equals(own.dueTotal())) {
                continue;
            }
            dueByGrade.merge(person.gradeOn(today), new BigDecimal(own.dueTotal()), BigDecimal::add);
        }
        Map<String, String> shown = new LinkedHashMap<>();
        dueByGrade.forEach((grade, total) -> shown.put(grade, total.setScale(2).toPlainString()));
        return new GradeDues(true, shown);
    }
}
