package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.setting.OpenToViewer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * A claim as the screen shows it; the amount always has two decimals, and so does the due fixed
     * when it was settled (null while its due is live). Who opened it is null while DRAFT. Stated
     * when bound by its person's last statement, to the due it had then (null when it had none). A
     * correction has the number of the claim it corrects (null for any other claim). Its status log
     * is oldest first. Locked by the user whose open editor holds it (null when none). A late entry
     * when added while its period was soft-closed. Reminded on the last day a reminder was written (null when none). Its external reference is null when it has none. Its tags are sorted (empty when none). Its void reason is null unless voided with one. Its comments are oldest first. Its batch is the number of the bulk settle that settled it (null when none).
     */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects,
            LocalDate dueBy, List<LogLineView> log, LocalDate deferredUntil,
            int proofs, String splitFrom, String lockedBy, boolean lateEntry, LocalDate remindedOn,
            String ref, List<String> tags, String voidReason, List<CommentView> comments,
            Integer batch) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().setScale(2).toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().setScale(2).toPlainString(),
                    claim.getCorrects(), claim.getDueBy(), claim.getLog().stream().map(LogLineView::of).toList(),
                    claim.getDeferredUntil(), claim.getProofs(), claim.getSplitFrom(),
                    claim.getLockedBy(), claim.isLateEntry(), claim.getRemindedOn(),
                    claim.getRef(), claim.getTags(), claim.getVoidReason(),
                    claim.getComments().stream().map(CommentView::of).toList(),
                    claim.getSettleBatch());
        }
    }

    /** A line of a claim's status log; its due has two decimals (null when it had none). */
    public record LogLineView(ClaimStatus status, String by, LocalDate date, String due) {
        static LogLineView of(ClaimLogEntry entry) {
            return new LogLineView(entry.getStatus(), entry.getBy(), entry.getDate(),
                    entry.getDue() == null ? null : entry.getDue().setScale(2).toPlainString());
        }
    }

    /** A comment on a claim: who wrote it, the sheet's today then, and its text. */
    public record CommentView(String by, LocalDate date, String text) {
        static CommentView of(ClaimComment comment) {
            return new CommentView(comment.getBy(), comment.getDate(), comment.getText());
        }
    }

    /**
     * A claim's amount (up to two decimals; negative for a correction), date (blank: the sheet's today),
     * note and, for a correction, the number of the claim it corrects.
     */
    public record ClaimRequest(
            @NotNull @Pattern(regexp = "-?\\d{1,12}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 500) String note,
            @Size(max = 220) @Pattern(regexp = "\\S+") String corrects,
            @Size(max = 200) String ref,
            @Size(max = 1000) String tags) {

        List<String> tagsValue() {
            return Claim.tagsOf(tags);
        }

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }

        String correctsValue() {
            return corrects == null || corrects.isEmpty() ? null : corrects;
        }

        String refValue() {
            return ref == null || ref.isBlank() ? null : ref.strip();
        }

        String noteValue() {
            return note == null ? "" : note.strip();
        }
    }

    /** Why a steward rejects a PENDING claim. */
    public record RejectRequest(@NotBlank @Size(max = 200) @Pattern(regexp = "\\S+( \\S+)*") String reason) {}

    /** A comment's text: not blank. */
    public record CommentRequest(@NotBlank @Size(max = 500) String text) {}

    /** The date an OPEN claim is deferred until: after the sheet's today. */
    public record DeferRequest(@NotNull LocalDate until) {}

    /** The new date of an OPEN or PENDING claim a steward re-dates. */
    public record RedateRequest(@NotNull LocalDate date) {}

    /** The person on the sheet a claim is moved to. */
    public record ReassignRequest(@NotNull Long personId) {}

    /** The amount split off an OPEN claim: more than 0.00 and less than its amount. */
    public record SplitRequest(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }
    }

    /** A split claim as it now is, and the new claim split off it. */
    public record SplitView(ClaimView original, ClaimView split) {}

    /** The reason a claim is voided with, from the sheet's list; none for a sheet without one. */
    public record VoidRequest(@Size(max = 200) String reason) {}

    /** The claims selected for a bulk change, by id. */
    public record BulkRequest(@NotNull @Size(max = 1000) Set<@NotNull Long> ids) {}

    /** How many of the selected claims a bulk open opened, and how many it refused. */
    public record BulkOpenView(int opened, int refused) {}

    /**
     * How many of the selected claims a bulk settle settled (or sent to PENDING), and how many it refused;
     * the sheet's last batch number after it (null while there has been none).
     */
    public record BulkSettleView(int settled, int refused, Integer lastBatch) {}

    /** The claims selected for a bulk move, by id, and the person on the sheet they are moved to. */
    public record BulkReassignRequest(@NotNull @Size(max = 1000) Set<@NotNull Long> ids, @NotNull Long personId) {}

    /** How many of the selected claims a bulk move moved, and how many it refused. */
    public record BulkReassignView(int reassigned, int refused) {}

    /** Pasted lines of name,amount,date,note, one claim each. */
    public record PasteRequest(@NotNull @Size(max = 200000) String text) {}

    /** The claims a paste added, in the order of their lines. */
    public record PasteView(int added, List<ClaimView> claims) {}

    private final ClaimService service;
    private final ClaimBulkService bulk;
    private final ClaimPasteService paste;

    public ClaimController(ClaimService service, ClaimBulkService bulk, ClaimPasteService paste) {
        this.service = service;
        this.bulk = bulk;
        this.paste = paste;
    }

    /** A reference already used on the sheet: a conflict of its own problem type. */
    @ExceptionHandler(DuplicateClaimRefException.class)
    public ResponseEntity<ProblemDetail> duplicateRef() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "That reference is already used on this sheet.");
        problem.setType(URI.create(DuplicateClaimRefException.TYPE));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.add(sheetId, user, personId, request.amountValue(), request.date(), request.noteValue(),
                request.correctsValue(), request.refValue(), request.tagsValue()));
    }

    /** Every tag ever used on a claim of the sheet, sorted. */
    @GetMapping("/claims/tags")
    public List<String> tags(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.tags(sheetId);
    }

    @PostMapping("/claims/paste")
    public PasteView paste(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PasteRequest request) {
        List<ClaimView> added = paste.paste(sheetId, user, request.text()).stream().map(ClaimView::of).toList();
        return new PasteView(added.size(), added);
    }

    @PutMapping("/claims/{id}")
    public ClaimView edit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.edit(sheetId, user, id, request.amountValue(), request.date(), request.noteValue(),
                request.correctsValue(), request.refValue(), request.tagsValue()));
    }

    @PostMapping("/claims/{id}/edit")
    public ClaimView lock(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.lock(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/cancel")
    public ClaimView unlock(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.unlock(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
    }

    @OpenToViewer
    @PostMapping("/claims/open")
    public BulkOpenView bulkOpen(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkRequest request) {
        ClaimBulkService.Result result = bulk.open(sheetId, user, request.ids());
        return new BulkOpenView(result.done(), result.refused());
    }

    @OpenToViewer
    @PostMapping("/claims/settle")
    public BulkSettleView bulkSettle(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkRequest request) {
        ClaimBulkService.Result result = bulk.settle(sheetId, user, request.ids());
        return new BulkSettleView(result.done(), result.refused(), bulk.lastSettleBatch(sheetId));
    }

    @OpenToViewer
    @PostMapping("/claims/reassign")
    public BulkReassignView bulkReassign(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkReassignRequest request) {
        ClaimBulkService.Result result = bulk.reassign(sheetId, user, request.ids(), request.personId());
        return new BulkReassignView(result.done(), result.refused());
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user, id, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) @Valid VoidRequest request) {
        return ClaimView.of(service.voidClaim(sheetId, user, id, request == null ? null : request.reason()));
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.approve(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RejectRequest request) {
        return ClaimView.of(service.reject(sheetId, user, id, request.reason()));
    }

    @PostMapping("/claims/{id}/resettle")
    public ClaimView resettle(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.resettle(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/defer")
    public ClaimView defer(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DeferRequest request) {
        return ClaimView.of(service.defer(sheetId, user, id, request.until()));
    }

    @PostMapping("/claims/{id}/redate")
    public ClaimView redate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RedateRequest request) {
        return ClaimView.of(service.redate(sheetId, user, id, request.date()));
    }

    @PostMapping("/claims/{id}/proof")
    public ClaimView addProof(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.addProof(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/comments")
    public ClaimView comment(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CommentRequest request) {
        return ClaimView.of(service.comment(sheetId, user, id, request.text().strip()));
    }

    @PostMapping("/claims/{id}/remind")
    public ClaimView remind(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.remind(sheetId, user, id));
    }

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReassignRequest request) {
        return ClaimView.of(service.reassign(sheetId, user, id, request.personId()));
    }

    @PostMapping("/claims/{id}/split")
    public SplitView split(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SplitRequest request) {
        ClaimService.Split split = service.split(sheetId, user, id, request.amountValue());
        return new SplitView(ClaimView.of(split.original()), ClaimView.of(split.split()));
    }
}
