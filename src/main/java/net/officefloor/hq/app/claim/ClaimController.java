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
 * their claims and claimed; so does opening, settling, approving, rejecting or voiding one. Opening a selection of claims
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

    /** Pasted lines of name,amount,date,note, one claim per line. */
    public record PasteForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** How many claims a paste added. */
    public record PasteResult(int added) {
    }

    /** The claims selected to be opened together. */
    public record BulkOpenForm(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    /** Why a PENDING claim is rejected. */
    public record RejectForm(@NotBlank @Size(max = 200) String reason) {
    }

    /** The person a claim is reassigned to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /** The date a claim is deferred until. */
    public record DeferForm(@NotNull LocalDate until) {
    }

    public record Refusal(String message) {
    }

    private final ClaimService claims;
    private final QuickAddService quick;
    private final PasteClaimsService paste;
    private final BulkOpenService bulkOpen;
    private final BulkSettleService bulkSettle;
    private final PersonService people;
    private final RateService rates;
    private final StatementService statements;

    public ClaimController(ClaimService claims, QuickAddService quick, PasteClaimsService paste, BulkOpenService bulkOpen,
            BulkSettleService bulkSettle, PersonService people, RateService rates, StatementService statements) {
        this.bulkOpen = bulkOpen;
        this.bulkSettle = bulkSettle;
        this.claims = claims;
        this.quick = quick;
        this.paste = paste;
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

    /** Paste DRAFT claims, a line each, for people on the sheet; one refused line adds none. */
    @PostMapping("/claims/paste")
    public PasteResult paste(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PasteForm form) {
        return new PasteResult(paste.paste(sheetId.trim(), user.trim(), form.text()));
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

    /** Settle the selected claims in list order; answers how many were settled and how many refused. */
    @PostMapping("/claims/bulk-settle")
    public BulkSettleService.Result settleAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody BulkOpenForm form) {
        return bulkSettle.settle(sheetId.trim(), user.trim(), new LinkedHashSet<>(form.claimIds()));
    }

    @PostMapping("/claims/{claimId}/settle")
    public PersonView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.SETTLED);
    }

    /** A steward approves a PENDING claim, settling it. */
    @PostMapping("/claims/{claimId}/approve")
    public PersonView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.approve(sheet, user.trim(), claimId));
    }

    /** A steward re-settles a SETTLED claim, fixing its due again at today's figure. */
    @PostMapping("/claims/{claimId}/resettle")
    public PersonView resettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.resettle(sheet, user.trim(), claimId));
    }

    /** A steward rejects a PENDING claim with a reason, putting it back to OPEN. */
    @PostMapping("/claims/{claimId}/reject")
    public PersonView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody RejectForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.reject(sheet, user.trim(), claimId, form.reason().trim()));
    }

    /** A steward moves a claim to another person; answers with the person it left and the one it joined. */
    @PostMapping("/claims/{claimId}/reassign")
    public List<PersonView> reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ReassignForm form) {
        String sheet = sheetId.trim();
        long[] moved = claims.reassign(sheet, user.trim(), claimId, form.personId());
        return List.of(view(sheet, moved[0]), view(sheet, moved[1]));
    }

    /** Defer an OPEN claim until a date after the sheet's today. */
    @PostMapping("/claims/{claimId}/defer")
    public PersonView defer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody DeferForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.defer(sheet, user.trim(), claimId, form.until()));
    }

    /** Add a proof to a claim, raising its proofs count by one. */
    @PostMapping("/claims/{claimId}/proof")
    public PersonView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.addProof(sheet, user.trim(), claimId));
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

    /** Another save took a quick-added person's name first: refused, nothing changed. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameInUse() {
    }
}
