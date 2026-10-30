package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.rate.RateService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The people on a sheet. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/people")
@Validated
public class PersonController {

    /**
     * A claim as shown: its amount with two decimals, its date, its note, and its due: the amount at
     * the rate for its person's grade (null when the grade has no rate).
     */
    public record ClaimView(long id, String amount, LocalDate date, String note, String due) {

        static ClaimView of(Claim claim, BigDecimal pct) {
            return new ClaimView(claim.getId(), money(claim.getAmount()), claim.getDate(), claim.getNote(),
                    money(RateService.due(claim.getAmount(), pct)));
        }
    }

    /**
     * A person with their claims, newest first, claimed: the sum of the claims' amounts, and
     * dueTotal: claimed at the rate for their grade (null when the grade has no rate).
     */
    public record PersonView(long id, String name, String grade, List<ClaimView> claims, String claimed,
            String dueTotal) {

        /** The person as shown, given the sheet's rate for each grade. */
        public static PersonView of(Person person, List<Claim> claims, Map<String, BigDecimal> rates) {
            BigDecimal pct = rates.get(person.getGrade());
            BigDecimal claimed = claims.stream().map(Claim::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new PersonView(person.getId(), person.getName(), person.getGrade(),
                    claims.stream().map(c -> ClaimView.of(c, pct)).toList(), money(claimed),
                    money(RateService.due(claimed, pct)));
        }
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade) {
    }

    private final PersonService service;
    private final ClaimService claims;
    private final RateService rates;

    public PersonController(PersonService service, ClaimService claims, RateService rates) {
        this.service = service;
        this.claims = claims;
        this.rates = rates;
    }

    @GetMapping
    public List<PersonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        Map<Long, List<Claim>> bySheet = claims.bySheet(sheet);
        Map<String, BigDecimal> byGrade = rates.byGrade(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, bySheet.getOrDefault(p.getId(), List.of()), byGrade)).toList();
    }

    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.add(sheet, user.trim(), form.name(), form.grade()), List.of(),
                rates.byGrade(sheet));
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.edit(sheet, user.trim(), personId, form.name(), form.grade()),
                claims.forPerson(sheet, personId), rates.byGrade(sheet));
    }

    /** Another save took the name first: the unique name on the sheet refuses this one. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }
}
