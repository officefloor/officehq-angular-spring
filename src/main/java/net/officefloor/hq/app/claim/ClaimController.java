package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import net.officefloor.hq.app.access.OverClerkLimit;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's claims. Adding or editing a claim answers with the person it belongs to, carrying
 * their claims and claimed; so does opening, settling or voiding one. Opening a selection of claims
 * together answers how many were opened and how many refused. The signed-in user's name comes in the {@code X-User} header.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /**
     * An amount with up to two decimals, negative for a correction, which names the number of the claim
     * it corrects; a blank date takes the sheet's today.
     */
    public record ClaimForm(@NotNull @Pattern(regexp = "-?\\d{1,12}(\\.\\d{1,2})?") String amount,
            LocalDate date, @Size(max = 500) String note, @Pattern(regexp = "[1-9]\\d{0,8}|.{1,100}-[1-9]\\d{0,8}") String corrects) {
    }

    /** A quick-add: a person's name and a claim's amount. */
    public record QuickAddForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {
    }

    /** The claims selected to be opened together. */
    public record BulkOpenForm(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    public record Refusal(String message) {
    }

    private final ClaimService claims;
    private final QuickAddService quick;
    private final BulkOpenService bulkOpen;
    private final PersonService people;
    private final RateService rates;
    private final StatementService statements;

    public ClaimController(ClaimService claims, QuickAddService quick, BulkOpenService bulkOpen, PersonService people,
            RateService rates, StatementService statements) {
        this.bulkOpen = bulkOpen;
        this.claims = claims;
        this.quick = quick;
        this.people = people;
        this.rates = rates;
        this.statements = statements;
    }

    @PostMapping("/people/{personId}/claims")
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        String sheet = sheetId.trim();
        long id = claims.add(sheet, user.trim(), personId, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects());
        return view(sheet, id);
    }

    /** Quick-add a DRAFT claim for a person by name, adding the person when the name is new. */
    @PostMapping("/quick-add")
    public PersonView quickAdd(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody QuickAddForm form) {
        String sheet = sheetId.trim();
        long id = quick.add(sheet, user.trim(), form.name(), new BigDecimal(form.amount()));
        return view(sheet, id);
    }

    @PutMapping("/claims/{claimId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        String sheet = sheetId.trim();
        long id = claims.edit(sheet, user.trim(), claimId, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects());
        return view(sheet, id);
    }

    @PostMapping("/claims/{claimId}/open")
    public PersonView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.OPEN);
    }

    /** Open the selected claims, numbered in list order; answers how many were opened and how many refused. */
    @PostMapping("/claims/bulk-open")
    public BulkOpenService.Result openAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody BulkOpenForm form) {
        return bulkOpen.open(sheetId.trim(), user.trim(), new LinkedHashSet<>(form.claimIds()));
    }

    @PostMapping("/claims/{claimId}/settle")
    public PersonView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.SETTLED);
    }

    @PostMapping("/claims/{claimId}/void")
    public PersonView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.VOID);
    }

    private PersonView move(String sheetId, long claimId, String user, ClaimStatus next) {
        String sheet = sheetId.trim();
        return view(sheet, claims.move(sheet, user.trim(), claimId, next));
    }

    private PersonView view(String sheetId, long personId) {
        return PersonView.of(people.get(sheetId, personId), claims.forPerson(sheetId, personId),
                rates.table(sheetId), statements.forPerson(sheetId, personId), people.patrons(sheetId, personId));
    }

    /** A claim dated inside a closed period: refused, nothing changed. */
    @ExceptionHandler(ClosedPeriodRefused.class)
    public ResponseEntity<Refusal> closed(ClosedPeriodRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** A clerk settling a claim whose due is above the clerk limit: refused, nothing changed. */
    @ExceptionHandler(OverClerkLimit.class)
    public ResponseEntity<Refusal> overLimit(OverClerkLimit e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another save took a quick-added person's name first: refused, nothing changed. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }
}
