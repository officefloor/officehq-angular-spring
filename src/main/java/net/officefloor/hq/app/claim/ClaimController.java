package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing, adding and editing the claims on a sheet, and changing their status. */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    /** An amount: 0.00 or more, with up to two decimals. */
    public static final String AMOUNT = "\\d{1,12}(\\.\\d{1,2})?";

    /** A claim's amount: as {@link #AMOUNT}, or below zero for a correction. */
    public static final String SIGNED_AMOUNT = "-?" + AMOUNT;

    public record ListClaimsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddClaimRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long personId, @NotNull @Pattern(regexp = SIGNED_AMOUNT) String amount, LocalDate date,
            @Size(max = 1000) String note, @Size(max = 120) String corrects, @Size(max = 120) String ref,
            @Size(max = 500) String tags) {
    }

    public record UpdateClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotNull @Pattern(regexp = SIGNED_AMOUNT) String amount, LocalDate date, @Size(max = 1000) String note,
            @Size(max = 120) String corrects, @Size(max = 120) String ref,
            @Size(max = 500) String tags) {
    }

    public record UpdateClaimAmountRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotNull @Pattern(regexp = SIGNED_AMOUNT) String amount) {
    }

    public record PasteClaimsRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull @Size(max = 200000) String text) {
    }

    /** What a paste added: how many claims, and the claims in the order they were added. */
    public record PasteClaimsResponse(int added, List<ClaimResponse> claims) {
    }

    /** A proof to add to a claim: its kind, receipt or photo, or blank or null for none chosen (a receipt). */
    public record ProofRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @Size(max = 20) String kind) {
    }

    public record AssignReviewRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @Size(max = 100) String assignee) {
    }

    public record ClaimStatusRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id) {
    }

    /** {@code on}: the date the claim is settled as of; none for the sheet's today. */
    public record SettleClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, LocalDate on) {
    }

    public record VoidClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @Size(max = 200) String reason) {
    }

    public record RejectClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotBlank @Size(max = 200) @Pattern(regexp = "\\s*\\S+\\s*") String reason) {
    }

    public record CommentClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull @Size(max = 1000) String text) {
    }

    public record AddLineRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotBlank @Size(max = 200) String label,
            @NotNull @Min(1) @Max(1000000) Integer qty, @NotNull @Pattern(regexp = AMOUNT) String unit) {
    }

    public record ShareClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull Long holder,
            @NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String share) {
    }

    public record RedateClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull LocalDate date) {
    }

    public record DeferClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull LocalDate until) {
    }

    public record ReassignClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull Long personId) {
    }

    public record SplitClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotNull @Pattern(regexp = AMOUNT) String amount) {
    }

    /** What a split did: the original claim with the rest of its amount, and the new claim split off it. */
    public record SplitClaimResponse(ClaimResponse original, ClaimResponse split) {
    }

    public record BulkOpenRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    public record BulkOpenResponse(int opened, int refused, List<ClaimResponse> claims) {
    }

    public record BulkReassignRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 1000) List<@NotNull Long> ids, @NotNull Long personId) {
    }

    public record BulkReassignResponse(int reassigned, int refused, List<ClaimResponse> claims) {
    }

    public record BulkRedateRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 1000) List<@NotNull Long> ids, @NotNull LocalDate date) {
    }

    public record BulkRedateResponse(int redated, int refused, List<ClaimResponse> claims) {
    }

    public record BulkVoidRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 1000) List<@NotNull Long> ids, @Size(max = 200) String reason) {
    }

    public record BulkVoidResponse(int voided, int refused, List<ClaimResponse> claims) {
    }

    /** {@code lastBatch}: the sheet's last settle batch number after the bulk settle; none when there has been none. */
    public record BulkSettleResponse(int settled, int refused, List<ClaimResponse> claims, Integer lastBatch) {
    }

    public record ClaimResponse(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy,
            String editedBy,
            boolean stated, String statedDue, String corrects, LocalDate dueBy, List<ClaimLogLine> log,
            LocalDate deferredUntil, int proofs, String splitFrom, String lockedBy,
            boolean lateEntry, LocalDate remindedOn, String ref, List<String> tags, String voidReason,
            List<ClaimCommentLine> comments, Integer batch, List<ClaimLineLine> lines, Long addedSeq,
            LocalDate reviewOn, boolean waived, String statedCharged, int proofsReceipt, int proofsPhoto,
            boolean held, List<ClaimShareLine> shares, String reviewAssigned, LocalDate pendingOn) {
    }

    /** One of the people a claim is shared with, in the order added: the holder's id and their share. */
    public record ClaimShareLine(long holder, String share) {
    }

    /** One line of a claim, in the order added: its label, quantity and unit amount. */
    public record ClaimLineLine(String label, int qty, String unit) {
    }

    /** One comment on a claim, oldest first. */
    public record ClaimCommentLine(String by, LocalDate date, String text) {
    }

    /** One line of a claim's status log, oldest first. */
    public record ClaimLogLine(ClaimStatus status, String by, LocalDate date, String due) {
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static ClaimResponse response(Claim claim) {
        return new ClaimResponse(claim.getId(), claim.getPersonId(),
                claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), claim.getDate(),
                claim.getNote(), claim.getStatus(), claim.getNumber(),
                money(claim.getSettledDue()), claim.getOpenedBy(), claim.getEditedBy(), claim.isStated(), money(claim.getStatedDue()),
                claim.getCorrects(), claim.getDueBy(),
                claim.getLog().stream().map(l -> new ClaimLogLine(l.getStatus(), l.getBy(), l.getDate(), l.getDue()))
                        .toList(),
                claim.getDeferredUntil(), claim.getProofs(), claim.getSplitFrom(), claim.getLockedBy(),
                claim.isLateEntry(), claim.getRemindedOn(), claim.getRef(), claim.getTags(),
                claim.getVoidReason(),
                claim.getComments().stream().map(c -> new ClaimCommentLine(c.getBy(), c.getDate(), c.getText()))
                        .toList(),
                claim.getBatch(),
                claim.getLines().stream().map(l -> new ClaimLineLine(l.getLabel(), l.getQty(), money(l.getUnit())))
                        .toList(),
                claim.getAddedSeq(), claim.getReviewOn(), claim.isWaived(),
                money(claim.getStatedCharged()), claim.proofsOf(Claim.RECEIPT), claim.proofsOf(Claim.PHOTO),
                claim.isHeld(),
                claim.getShares().stream().map(s -> new ClaimShareLine(s.getHolderId(), money(s.getShare())))
                        .toList(),
                claim.getReviewAssigned(), claim.getPendingOn());
    }

    public static BigDecimal amount(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The number a correction names, trimmed; null when none is named. */
    private static String corrects(String corrects) {
        return corrects == null || corrects.isBlank() ? null : corrects.trim();
    }

    /** An external reference, trimmed; null when blank. */
    public static String ref(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }

    /** Comma separated tags: each trimmed and lower-cased, blanks and repeats dropped, sorted. */
    public static List<String> tags(String tags) {
        return tags == null ? List.of()
                : Arrays.stream(tags.split(",")).map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                        .distinct().sorted().toList();
    }

    private static String note(String note) {
        return note == null ? "" : note.trim();
    }

    private final ClaimService service;
    private final ClaimPasteService pasteService;
    private final ClaimAsOf asOf;

    public ClaimController(ClaimService service, ClaimPasteService pasteService, ClaimAsOf asOf) {
        this.service = service;
        this.pasteService = pasteService;
        this.asOf = asOf;
    }

    @PostMapping("/list")
    public List<ClaimResponse> list(@Valid @RequestBody ListClaimsRequest req) {
        return service.list(req.sheet().trim()).stream().map(ClaimController::response).toList();
    }

    @PostMapping("/add")
    public ClaimResponse add(@Valid @RequestBody AddClaimRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.personId(), amount(req.amount()), req.date(),
                note(req.note()), corrects(req.corrects()), ref(req.ref()), tags(req.tags())));
    }

    /** Every tag ever used on the sheet, sorted. */
    @PostMapping("/tags")
    public List<String> tags(@Valid @RequestBody ListClaimsRequest req) {
        return service.usedTags(req.sheet().trim());
    }

    @PostMapping("/paste")
    public PasteClaimsResponse paste(@Valid @RequestBody PasteClaimsRequest req) {
        List<ClaimResponse> added = pasteService.paste(req.sheet().trim(), req.user().trim(), req.text()).stream()
                .map(ClaimController::response).toList();
        return new PasteClaimsResponse(added.size(), added);
    }

    @PostMapping("/update")
    public ClaimResponse update(@Valid @RequestBody UpdateClaimRequest req) {
        return response(service.update(req.sheet().trim(), req.user().trim(), req.id(), amount(req.amount()), req.date(),
                note(req.note()), corrects(req.corrects()), ref(req.ref()), tags(req.tags())));
    }

    @PostMapping("/amount")
    public ClaimResponse updateAmount(@Valid @RequestBody UpdateClaimAmountRequest req) {
        return response(service.updateAmount(req.sheet().trim(), req.user().trim(), req.id(), amount(req.amount())));
    }

    @PostMapping("/lock")
    public ClaimResponse lock(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.lock(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/unlock")
    public ClaimResponse unlock(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.unlock(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/open")
    public ClaimResponse open(@Valid @RequestBody ClaimStatusRequest req) {
        return changeStatus(req, ClaimStatus.OPEN);
    }

    @PostMapping("/settle")
    public ClaimResponse settle(@Valid @RequestBody SettleClaimRequest req) {
        String sheet = req.sheet().trim();
        if (req.on() == null) {
            return response(service.changeStatus(sheet, req.user().trim(), req.id(), ClaimStatus.SETTLED));
        }
        BigDecimal due = asOf.due(sheet, req.id(), req.on());
        return response(service.settleAsOf(sheet, req.user().trim(), req.id(), req.on(), due));
    }

    @PostMapping("/approve")
    public ClaimResponse approve(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.approve(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/reject")
    public ClaimResponse reject(@Valid @RequestBody RejectClaimRequest req) {
        return response(service.reject(req.sheet().trim(), req.user().trim(), req.id(), req.reason().trim()));
    }

    @PostMapping("/review")
    public ClaimResponse review(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.review(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/void")
    public ClaimResponse voidClaim(@Valid @RequestBody VoidClaimRequest req) {
        return response(service.voidClaim(req.sheet().trim(), req.user().trim(), req.id(), req.reason()));
    }

    @PostMapping("/delete")
    public ClaimResponse delete(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.delete(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/restore")
    public ClaimResponse restore(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.restore(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/resettle")
    public ClaimResponse resettle(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.resettle(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/defer")
    public ClaimResponse defer(@Valid @RequestBody DeferClaimRequest req) {
        return response(service.defer(req.sheet().trim(), req.user().trim(), req.id(), req.until()));
    }

    @PostMapping("/redate")
    public ClaimResponse redate(@Valid @RequestBody RedateClaimRequest req) {
        return response(service.redate(req.sheet().trim(), req.user().trim(), req.id(), req.date()));
    }

    @PostMapping("/proof")
    public ClaimResponse proof(@Valid @RequestBody ProofRequest req) {
        String kind = req.kind() == null || req.kind().isBlank() ? null : req.kind().trim();
        return response(service.addProof(req.sheet().trim(), req.user().trim(), req.id(), kind));
    }

    @PostMapping("/comment")
    public ClaimResponse comment(@Valid @RequestBody CommentClaimRequest req) {
        return response(service.comment(req.sheet().trim(), req.user().trim(), req.id(), req.text().trim()));
    }

    @PostMapping("/line")
    public ClaimResponse addLine(@Valid @RequestBody AddLineRequest req) {
        return response(service.addLine(req.sheet().trim(), req.user().trim(), req.id(), req.label().trim(), req.qty(),
                amount(req.unit())));
    }

    @PostMapping("/share")
    public ClaimResponse share(@Valid @RequestBody ShareClaimRequest req) {
        return response(service.share(req.sheet().trim(), req.user().trim(), req.id(), req.holder(),
                amount(req.share())));
    }

    @PostMapping("/remind")
    public ClaimResponse remind(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.remind(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/waive")
    public ClaimResponse waive(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.waive(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/assign-review")
    public ClaimResponse assignReview(@Valid @RequestBody AssignReviewRequest req) {
        return response(service.assignReview(req.sheet().trim(), req.user().trim(), req.id(), req.assignee()));
    }

    @PostMapping("/hold")
    public ClaimResponse hold(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.toggleHold(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/reassign")
    public ClaimResponse reassign(@Valid @RequestBody ReassignClaimRequest req) {
        return response(service.reassign(req.sheet().trim(), req.user().trim(), req.id(), req.personId()));
    }

    @PostMapping("/split")
    public SplitClaimResponse split(@Valid @RequestBody SplitClaimRequest req) {
        ClaimService.Split done = service.split(req.sheet().trim(), req.user().trim(), req.id(), amount(req.amount()));
        return new SplitClaimResponse(response(done.original()), response(done.split()));
    }

    /** Split the amount off an OPEN claim and settle the new claim at once; refused whole when either step is. */
    @PostMapping("/settle-part")
    public SplitClaimResponse settlePart(@Valid @RequestBody SplitClaimRequest req) {
        ClaimService.Split done = service.partSettle(req.sheet().trim(), req.user().trim(), req.id(),
                amount(req.amount()));
        return new SplitClaimResponse(response(done.original()), response(done.split()));
    }

    @PostMapping("/bulk-open")
    public BulkOpenResponse bulkOpen(@Valid @RequestBody BulkOpenRequest req) {
        ClaimService.BulkOpened done = service.bulkOpen(req.sheet().trim(), req.user().trim(), req.ids());
        return new BulkOpenResponse(done.opened(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList());
    }

    @PostMapping("/bulk-settle")
    public BulkSettleResponse bulkSettle(@Valid @RequestBody BulkOpenRequest req) {
        ClaimService.BulkSettled done = service.bulkSettle(req.sheet().trim(), req.user().trim(), req.ids());
        return new BulkSettleResponse(done.settled(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList(),
                done.lastBatch() == 0 ? null : done.lastBatch());
    }

    @PostMapping("/bulk-reassign")
    public BulkReassignResponse bulkReassign(@Valid @RequestBody BulkReassignRequest req) {
        ClaimService.BulkReassigned done = service.bulkReassign(req.sheet().trim(), req.user().trim(), req.ids(),
                req.personId());
        return new BulkReassignResponse(done.reassigned(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList());
    }

    @PostMapping("/bulk-redate")
    public BulkRedateResponse bulkRedate(@Valid @RequestBody BulkRedateRequest req) {
        ClaimService.BulkRedated done = service.bulkRedate(req.sheet().trim(), req.user().trim(), req.ids(),
                req.date());
        return new BulkRedateResponse(done.redated(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList());
    }

    @PostMapping("/bulk-void")
    public BulkVoidResponse bulkVoid(@Valid @RequestBody BulkVoidRequest req) {
        ClaimService.BulkVoided done = service.bulkVoid(req.sheet().trim(), req.user().trim(), req.ids(),
                req.reason());
        return new BulkVoidResponse(done.voided(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList());
    }

    private ClaimResponse changeStatus(ClaimStatusRequest req, ClaimStatus next) {
        return response(service.changeStatus(req.sheet().trim(), req.user().trim(), req.id(), next));
    }
}
