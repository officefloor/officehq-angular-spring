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
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
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
     * A claim as shown: its amount with two decimals, its date, its note, its due: the amount at
     * the rate for its person's grade in force on its date (null when none is) or, once settled, the due fixed
     * then, its status, and its number (null while it has none).
     */
    public record ClaimView(long id, String amount, LocalDate date, String note, String due, ClaimStatus status,
            Integer number) {

        static ClaimView of(Claim claim, String grade, RateTable rates) {
            return new ClaimView(claim.getId(), money(claim.getAmount()), claim.getDate(), claim.getNote(),
                    money(claim.getSettledDue() != null ? claim.getSettledDue()
                            : RateService.due(claim.getAmount(), rates.on(grade, claim.getDate()))), claim.getStatus(),
                    claim.getNumber());
        }
    }

    /**
     * A person with their claims, newest first, claimed: the sum of the amounts of the claims that
     * count (OPEN and SETTLED), and
     * dueTotal: claimed at the rate for their grade in force on the sheet's today (null when none is).
     */
    public record PersonView(long id, String name, String grade, List<ClaimView> claims, String claimed,
            String dueTotal) {

        /** The person as shown, given the sheet's rates. */
        public static PersonView of(Person person, List<Claim> claims, RateTable rates) {
            BigDecimal pct = rates.current(person.getGrade());
            BigDecimal claimed = claims.stream().filter(c -> c.getStatus().counts()).map(Claim::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new PersonView(person.getId(), person.getName(), person.getGrade(),
                    claims.stream().map(c -> ClaimView.of(c, person.getGrade(), rates)).toList(), money(claimed),
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
        RateTable table = rates.table(sheet);
        return service.list(sheet).stream()
                .map(p -> PersonView.of(p, bySheet.getOrDefault(p.getId(), List.of()), table)).toList();
    }

    @PostMapping
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.add(sheet, user.trim(), form.name(), form.grade()), List.of(),
                rates.table(sheet));
    }

    @PutMapping("/{personId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody PersonForm form) {
        String sheet = sheetId.trim();
        return PersonView.of(service.edit(sheet, user.trim(), personId, form.name(), form.grade()),
                claims.forPerson(sheet, personId), rates.table(sheet));
    }

    /** Another save took the name first: the unique name on the sheet refuses this one. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }
}
