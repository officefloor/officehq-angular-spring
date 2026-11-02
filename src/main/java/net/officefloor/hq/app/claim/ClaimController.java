package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** What the screen shows of a claim; the amount, and the due fixed when it was settled (null
     *  when there is none), as text with two decimals; who opened it (null when never opened);
     *  whether the last statement bound it, with the due it bound (null when none) and whether it
     *  bound it to n/a; and the number
     *  of the claim it corrects (null when it is not a correction); the date its figures round by
     *  (that of the claim a correction corrects, or else its own); and where it is listed (newest
     *  first by this); and its status log, oldest first. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, boolean stated,
            String statedDue, boolean statedNa, String corrects, LocalDate roundsBy, long listed, LocalDate dueBy,
            List<LogLineView> log) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().setScale(2).toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().setScale(2).toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().setScale(2).toPlainString(),
                    claim.isStatedNa(), claim.getCorrects(), claim.roundsBy(), claim.getListed(), claim.getDueBy(),
                    claim.getLog().stream().map(LogLineView::of).toList());
        }
    }

    /** A line of a claim's status log: the status, who changed it to that, the sheet's today then, and
     *  the claim's due at that moment as text with two decimals (null when it had none). */
    public record LogLineView(ClaimStatus status, String by, LocalDate on, String due) {
        static LogLineView of(ClaimLog line) {
            return new LogLineView(line.getStatus(), line.getByUser(), line.getOn(),
                    line.getDue() == null ? null : line.getDue().setScale(2).toPlainString());
        }
    }

    /** The claim form: a required amount with up to two decimals (a negative one is a correction), an
     *  optional date (the sheet's today when left out), a note that may be empty, and the number of
     *  the settled claim a correction corrects. */
    public record ClaimForm(@NotNull @Pattern(regexp = "\\s*-?\\d{1,12}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate date, @Size(max = 500) String note, @Size(max = 120) String corrects) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }

        String noteValue() {
            return note == null ? "" : note.trim();
        }

        /** The number a correction names (1, 2, ... or P1-1, X-1, ...); null when left blank. */
        String correctsValue() {
            return corrects == null || corrects.isBlank() ? null : corrects.trim();
        }
    }

    /** The claims a bulk action is taken on, by id. */
    public record BulkForm(@NotNull @Size(max = 1000) Set<@NotNull Long> claimIds) {
    }

    /** How many claims a bulk open opened, and how many it refused. */
    public record BulkOpened(int opened, int refused) {
    }

    /** How many claims a bulk settle settled (or left pending a steward's approval), and how many it
     *  refused. */
    public record BulkSettled(int settled, int refused) {
    }

    private final ClaimService service;
    private final ClaimBulkService bulk;

    public ClaimController(ClaimService service, ClaimBulkService bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId, form.amountValue(), form.date(), form.noteValue(),
                form.correctsValue()));
    }

    @PutMapping("/claims/{claimId}")
    public ClaimView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.edit(sheetId.trim(), user.trim(), claimId, form.amountValue(), form.date(), form.noteValue(),
                form.correctsValue()));
    }

    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/open")
    public BulkOpened openAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm form) {
        ClaimBulkService.Outcome outcome = bulk.open(sheetId.trim(), user.trim(), form.claimIds());
        return new BulkOpened(outcome.done(), outcome.refused());
    }

    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/settle")
    public BulkSettled settleAll(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkForm form) {
        ClaimBulkService.Outcome outcome = bulk.settle(sheetId.trim(), user.trim(), form.claimIds());
        return new BulkSettled(outcome.done(), outcome.refused());
    }

    @PostMapping("/claims/{claimId}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.resettle(sheetId.trim(), user.trim(), claimId));
    }

    /** The reason a steward gives for rejecting a pending claim. */
    public record RejectForm(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.approve(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RejectForm form) {
        return ClaimView.of(service.reject(sheetId.trim(), user.trim(), claimId, form.reason().trim()));
    }

    /** The person a steward moves a claim to. */
    public record ReassignForm(@NotNull Long personId) {
    }

    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ReassignForm form) {
        return ClaimView.of(service.reassign(sheetId.trim(), user.trim(), claimId, form.personId()));
    }

    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID));
    }
}
