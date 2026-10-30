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
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * it corrects; a blank date takes the sheet's today; a blank external reference is none; tags are
     * comma separated (blank: none).
     */
    public record ClaimForm(@NotNull @Pattern(regexp = "-?\\d{1,12}(\\.\\d{1,2})?") String amount,
            LocalDate date, @Size(max = 500) String note, @Pattern(regexp = "[1-9]\\d{0,8}|.{1,100}-[1-9]\\d{0,8}") String corrects,
            @Size(max = 100) String ref, @Size(max = 1000) String tags) {
    }

    /** A quick-add: a person's name and a claim's amount. */
    public record QuickAddForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {
    }

    /** A DRAFT claim's new amount, entered on its row: up to two decimals, negative only for a correction. */
    public record AmountForm(@NotNull @Pattern(regexp = "-?\\d{1,12}(\\.\\d{1,2})?") String amount) {
    }

    /** Pasted lines of name,amount,date,note, one claim per line; a note may end in " #<reference>". */
    public record PasteForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** How many claims a paste added. */
    public record PasteResult(int added) {
    }

    /** The claims selected to be opened together. */
    public record BulkOpenForm(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    /** The claims selected to be moved together, and the person they are moved to. */
    public record BulkReassignForm(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds, @NotNull Long personId) {
    }

    /** Why a PENDING claim is rejected. */
    public record RejectForm(@NotBlank @Size(max = 200) String reason) {
    }

    /** The reason from the sheet's list a claim is voided with; none (null or blank) while the list is empty. */
    public record VoidForm(@Size(max = 1000) String reason) {
    }

    /** The person a claim is reassigned to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    /** The date a claim is deferred until. */
    public record DeferForm(@NotNull LocalDate until) {
    }

    /** The new date of a claim being re-dated. */
    public record RedateForm(@NotNull LocalDate date) {
    }

    /** The amount split off a claim, more than 0.00 and less than the claim's amount. */
    public record SplitForm(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {
    }

    /** A comment on a claim: not blank. */
    public record CommentForm(@NotBlank @Size(max = 1000) String text) {
    }

    /** The number of the sheet's last settle batch; null when there has been none. */
    public record SettleBatchView(Integer last) {
    }

    public record Refusal(String message) {
    }

    private final ClaimService claims;
    private final QuickAddService quick;
    private final PasteClaimsService paste;
    private final BulkOpenService bulkOpen;
    private final BulkSettleService bulkSettle;
    private final BulkReassignService bulkReassign;
    private final PersonService people;
    private final RateService rates;
    private final StatementService statements;
    private final Tags tags;

    public ClaimController(ClaimService claims, QuickAddService quick, PasteClaimsService paste, BulkOpenService bulkOpen,
            BulkSettleService bulkSettle, BulkReassignService bulkReassign, PersonService people, RateService rates,
            StatementService statements, Tags tags) {
        this.bulkReassign = bulkReassign;
        this.tags = tags;
        this.bulkOpen = bulkOpen;
        this.bulkSettle = bulkSettle;
        this.claims = claims;
        this.quick = quick;
        this.paste = paste;
        this.people = people;
        this.rates = rates;
        this.statements = statements;
    }

    /** Every tag ever used on the sheet, sorted. */
    @GetMapping("/tags")
    public List<String> tags(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return tags.used(sheetId.trim());
    }

    @PostMapping("/people/{personId}/claims")
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        String sheet = sheetId.trim();
        long id = claims.add(sheet, user.trim(), personId, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects(), form.ref(), form.tags());
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
                form.corrects(), form.ref(), form.tags());
        return view(sheet, id);
    }

    /** Change a DRAFT claim's amount on its row, by the claim form's rules. */
    @PutMapping("/claims/{claimId}/amount")
    public PersonView editAmount(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody AmountForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.editAmount(sheet, user.trim(), claimId, new BigDecimal(form.amount())));
    }

    /** Open a claim's editor, locking the claim for other users. */
    @PostMapping("/claims/{claimId}/lock")
    public PersonView lock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.lock(sheet, user.trim(), claimId));
    }

    /** Cancel a claim's editor, releasing its lock. */
    @PostMapping("/claims/{claimId}/unlock")
    public PersonView unlock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.unlock(sheet, user.trim(), claimId));
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

    /** The number of the sheet's last settle batch (null when none). */
    @GetMapping("/claims/settle-batch")
    public SettleBatchView settleBatch(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        int last = claims.lastSettleBatch(sheetId.trim());
        return new SettleBatchView(last == 0 ? null : last);
    }

    /** Move the selected claims to a person in list order; answers how many were moved and how many refused. */
    @PostMapping("/claims/bulk-reassign")
    public BulkReassignService.Result reassignAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody BulkReassignForm form) {
        return bulkReassign.reassign(sheetId.trim(), user.trim(), new LinkedHashSet<>(form.claimIds()),
                form.personId());
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

    /** A steward re-dates an OPEN or PENDING claim. */
    @PostMapping("/claims/{claimId}/redate")
    public PersonView redate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody RedateForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.redate(sheet, user.trim(), claimId, form.date()));
    }

    /** Split an amount off an OPEN claim into a new OPEN claim of the same person. */
    @PostMapping("/claims/{claimId}/split")
    public PersonView split(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody SplitForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.split(sheet, user.trim(), claimId, new BigDecimal(form.amount())));
    }

    /** Remind an OPEN or PENDING claim, once per sheet's today. */
    @PostMapping("/claims/{claimId}/remind")
    public PersonView remind(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.remind(sheet, user.trim(), claimId));
    }

    /** Add a proof to a claim, raising its proofs count by one. */
    @PostMapping("/claims/{claimId}/proof")
    public PersonView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.addProof(sheet, user.trim(), claimId));
    }

    /** Comment a claim, as the signed-in user on the sheet's today. */
    @PostMapping("/claims/{claimId}/comments")
    public PersonView comment(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody CommentForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.comment(sheet, user.trim(), claimId, form.text()));
    }

    /** Delete a DRAFT claim. */
    @PostMapping("/claims/{claimId}/delete")
    public PersonView delete(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.delete(sheet, user.trim(), claimId));
    }

    /** Restore a DELETED claim to a DRAFT, on the sheet's today it was deleted. */
    @PostMapping("/claims/{claimId}/restore")
    public PersonView restore(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, claims.restore(sheet, user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/void")
    public PersonView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody(required = false) VoidForm form) {
        String sheet = sheetId.trim();
        return view(sheet, claims.move(sheet, user.trim(), claimId, ClaimStatus.VOID, form == null ? null : form.reason()));
    }

    private PersonView move(String sheetId, long claimId, String user, ClaimStatus next) {
        String sheet = sheetId.trim();
        return view(sheet, claims.move(sheet, user.trim(), claimId, next));
    }

    private PersonView view(String sheetId, long personId) {
        Person person = people.get(sheetId, personId);
        return PersonView.of(person, claims.forPerson(sheetId, personId),
                rates.table(sheetId), statements.forPerson(sheetId, personId), people.patrons(sheetId, personId),
                people.clashing(sheetId, person));
    }

    /** A claim due by a date inside a closed period: refused, nothing changed. */
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
