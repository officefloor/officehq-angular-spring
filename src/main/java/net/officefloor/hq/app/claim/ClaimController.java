package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetAsOf;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
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

/** The claims on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** A claim, with who opened it (null until opened), whether it is bound to the figure it had at its
     * person's last statement, its status log and the date it was last deferred until (null when never
     * deferred; it is deferred while OPEN and the sheet's today is before that date), its proofs count, whether it was added while its period was soft-closed (one line per status it has had, oldest first); its amount, what was due on it when settled (null otherwise) and
     * the figure it is bound to (null when unbound or bound to no figure), what was due on it as it showed when
     * bound, late charge included (null when unbound, bound to no figure, or bound before that was kept), and the date it was last reminded
     * (null when never), are sent as text with two
     * decimals so they are never rounded on the way. Its comments follow, oldest first, each as
     * {@code <user> on <date>: <text>}. Its lines follow, in the order they were added, each as
     * {@code <label> <qty> x <unit>}. Its added mark is when it was first added, as a claim id (see
     * {@link Claim#addedMark}), which decides the forward-only rates it takes. Last come how many of its
     * proofs are of each kind (receipt, photo), by kind, then whether it is held. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, LocalDate dueBy, String note,
            ClaimStatus status, String number, String settledDue,
            String openedBy, boolean stated, String statedDue, String statedShown, String corrects, List<String> log,
            LocalDate deferredUntil, int proofs, String splitFrom, boolean lateEntry, LocalDate remindedOn,
            String ref, List<String> tags, String voidReason, List<String> comments, String editedBy,
            Integer settleBatch, List<String> lines, long added, boolean reviewLate, boolean waived,
            Map<String, Integer> proofsByKind, boolean held) {
        /** The claims as views, each with its status log and comments, in the order given. */
        public static List<ClaimView> allOf(List<Claim> claims, ClaimService service) {
            Map<Long, List<ClaimLog>> logs = service.logsOf(claims);
            Map<Long, List<ClaimComment>> comments = service.commentsOf(claims);
            Map<Long, List<ClaimLine>> lines = service.linesOf(claims);
            Map<String, LocalDate> lateBefore = new HashMap<>();
            return claims.stream().map(c -> of(c, logs.getOrDefault(c.getId(), List.of()),
                    comments.getOrDefault(c.getId(), List.of()), lines.getOrDefault(c.getId(), List.of()),
                    lateBefore.computeIfAbsent(c.getSheetId(), service::reviewLateBefore))).toList();
        }

        /** The claim as a view, with its status log. */
        public static ClaimView of(Claim claim, ClaimService service) {
            return allOf(List.of(claim), service).get(0);
        }

        /** The claim as a view, with its status log; a claim in REVIEW whose review day (its last REVIEW
         * entry in the log) is before {@code lateBefore} is late (none is when that is null). */
        static ClaimView of(Claim claim, List<ClaimLog> log, List<ClaimComment> comments, List<ClaimLine> lines,
                LocalDate lateBefore) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    claim.getDate(), claim.getDueBy(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    text(claim.getSettledDue()), claim.getOpenedBy(), claim.isStated(), text(claim.getStatedDue()), text(claim.getStatedShown()),
                    claim.getCorrects(), log.stream().map(ClaimView::line).toList(),
                    claim.getDeferredUntil(), claim.getProofs(), claim.getSplitFrom(),
                    claim.isLateEntry(), claim.getRemindedOn(), claim.getRef(), claim.getTags(),
                    claim.getVoidReason(), comments.stream().map(ClaimView::line).toList(), claim.getEditedBy(),
                    claim.getSettleBatch(), lines.stream().map(ClaimView::line).toList(), claim.addedMark(),
                    reviewLate(claim, log, lateBefore), claim.isWaived(), proofsByKind(claim), claim.isHeld());
        }

        /** How many of the claim's proofs are of each kind, by kind, in the order the kinds are offered. */
        private static Map<String, Integer> proofsByKind(Claim claim) {
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (String kind : Claim.PROOF_KINDS) byKind.put(kind, claim.getProofsOf(kind));
            return byKind;
        }

        /** Whether the claim is in REVIEW and its review day is before {@code lateBefore}. */
        private static boolean reviewLate(Claim claim, List<ClaimLog> log, LocalDate lateBefore) {
            if (lateBefore == null || claim.getStatus() != ClaimStatus.REVIEW) return false;
            LocalDate reviewDay = null;
            for (ClaimLog entry : log) {
                if (entry.getStatus() == ClaimStatus.REVIEW) reviewDay = entry.getDate();
            }
            return reviewDay != null && reviewDay.isBefore(lateBefore);
        }

        /** A line as shown: {@code <label> <qty> x <unit>}. */
        private static String line(ClaimLine line) {
            return line.getLabel() + " " + line.getQty() + " x " + line.unitText();
        }

        /** A comment as shown: {@code <user> on <date>: <text>}. */
        private static String line(ClaimComment comment) {
            return comment.getBy() + " on " + comment.getDate() + ": " + comment.getText();
        }

        /** A log line as shown: {@code <STATUS> by <user> on <date> due=<due>}. */
        private static String line(ClaimLog entry) {
            return entry.getStatus() + " by " + entry.getBy() + " on " + entry.getDate() + " due="
                    + (entry.getDue() == null ? "n/a" : text(entry.getDue()));
        }

        private static String text(BigDecimal figure) {
            return figure == null ? null : figure.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    /** A claim's amount (with up to two decimals; negative on a correction), date (the sheet's today
     * when left out), note (may be empty), on a correction, the number of the claim it corrects, and its
     * external reference (none when blank), and its tags, comma separated (none when blank). */
    public record ClaimRequest(
            @NotNull @Pattern(regexp = "\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate date,
            @Size(max = 500) String note,
            @Size(max = 120) String corrects,
            @Size(max = 120) String ref,
            @Size(max = 500) String tags) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }

        /** The number named, trimmed; null when none is. */
        String correctsValue() {
            return corrects == null || corrects.isBlank() ? null : corrects.trim();
        }

        /** The reference given, trimmed; null when none is. */
        String refValue() {
            return ref == null || ref.isBlank() ? null : ref.trim();
        }

        /** The tags given, as a claim keeps them (see {@link Claim#tagsOf}); null when none are. */
        String tagsValue() {
            return Claim.tagsOf(tags);
        }
    }

    /** Why a steward rejects a PENDING claim: one word, as it is written into the audit. */
    public record RejectRequest(@NotNull @Pattern(regexp = "\\s*\\S{1,200}\\s*") String reason) {
    }

    /** The reason from the sheet's list a claim is voided with; none when null or blank. */
    public record VoidRequest(@Size(max = 200) String reason) {
    }

    /** A comment on a claim: its text, which may not be blank. */
    public record CommentRequest(@NotBlank @Size(max = 1000) String text) {
    }

    /** A line added to a claim: its label (not blank), its quantity (a whole number, at least 1) and its
     * unit amount (with up to two decimals). */
    public record LineRequest(
            @NotBlank @Size(max = 200) String label,
            @NotNull @Pattern(regexp = "\\s*0*[1-9]\\d{0,5}\\s*") String qty,
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String unit) {

        int qtyValue() {
            return Integer.parseInt(qty.trim());
        }

        BigDecimal unitValue() {
            return new BigDecimal(unit.trim()).setScale(2);
        }
    }

    /** The date a claim is deferred until. */
    public record DeferRequest(@NotNull LocalDate until) {
    }

    /** The amount split off a claim: more than 0.00, with up to two decimals. */
    public record SplitRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    /** The date a claim is re-dated to. */
    public record RedateRequest(@NotNull LocalDate date) {
    }

    /** The person a claim is moved to, by id. */
    public record ReassignRequest(@NotNull Long personId) {
    }

    /** The claims a bulk change is asked of, by id. */
    public record BulkRequest(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    /** How a bulk open went: how many claims it opened and refused, and the claims it opened. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    /** How a bulk settle went: how many claims it settled (or left PENDING) and refused, and those claims,
     * and the number of the batch it made (null when it settled none). */
    public record BulkSettleView(int settled, int refused, List<ClaimView> claims, Integer batch) {
    }

    /** The claims a bulk move is asked of, by id, and the person they are moved to. */
    public record BulkReassignRequest(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds, @NotNull Long personId) {
    }

    /** How a bulk move went: how many claims it moved and refused, and the claims it moved. */
    public record BulkReassignView(int reassigned, int refused, List<ClaimView> claims) {
    }

    /** The claims a bulk re-date is asked of, by id, and the date they are re-dated to. */
    public record BulkRedateRequest(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds, @NotNull LocalDate date) {
    }

    /** How a bulk re-date went: how many claims it re-dated and refused, and the claims it re-dated. */
    public record BulkRedateView(int redated, int refused, List<ClaimView> claims) {
    }

    private final ClaimService service;
    /** The claims a bulk void is asked of, by id, and the reason from the sheet's list they are voided
     * with; none when null or blank. */
    public record BulkVoidRequest(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds, @Size(max = 200) String reason) {
    }

    /** How a bulk void went: how many claims it voided and refused, and the claims it voided. */
    public record BulkVoidView(int voided, int refused, List<ClaimView> claims) {
    }

    private final ClaimBulkService bulk;

    private final SheetHistory history;

    public ClaimController(ClaimService service, ClaimBulkService bulk, SheetHistory history) {
        this.history = history;
        this.service = service;
        this.bulk = bulk;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            SheetAsOf then = history.read(sheetId.trim(), asOf);
            Map<Long, List<ClaimLog>> logs = service.logsOf(then.claims());
            Map<Long, List<ClaimComment>> comments = service.commentsOf(then.claims());
            Map<Long, List<ClaimLine>> lines = service.linesOf(then.claims());
            return then.claims().stream()
                    .map(c -> ClaimView.of(c, then.logged(logs.getOrDefault(c.getId(), List.of())),
                            comments.getOrDefault(c.getId(), List.of()).stream()
                                    .filter(k -> !k.getDate().isAfter(asOf)).toList(),
                            lines.getOrDefault(c.getId(), List.of()),
                            then.reviewSla() == null ? null : then.today().minusDays(then.reviewSla())))
                    .toList();
        }
        return views(service.list(sheetId.trim()));
    }

    /** Every tag ever used on the sheet's claims, sorted. */
    @GetMapping("/tags")
    public List<String> tags(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.tags(sheetId.trim());
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return view(service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.date(),
                request.note(), request.correctsValue(), request.refValue(), request.tagsValue()));
    }

    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return view(service.update(sheetId.trim(), user.trim(), claimId, request.amountValue(), request.date(),
                request.note(), request.correctsValue(), request.refValue(), request.tagsValue()));
    }

    @PostMapping("/claims/{claimId}/lock")
    public ClaimView lock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.lock(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/unlock")
    public ClaimView unlock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.unlock(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{claimId}/review")
    public ClaimView review(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.review(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.approve(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RejectRequest request) {
        return view(service.reject(sheetId.trim(), user.trim(), claimId, request.reason().trim()));
    }

    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) @Valid VoidRequest request) {
        String reason = request == null ? null : request.reason();
        return view(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID, reason));
    }

    @PostMapping("/claims/{claimId}/delete")
    public ClaimView delete(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.delete(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/restore")
    public ClaimView restore(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.restore(sheetId.trim(), user.trim(), claimId));
    }

    /** The kind of a proof: receipt or photo; blank or null when none is chosen (a receipt). */
    public record ProofRequest(@Pattern(regexp = "\\s*(receipt|photo)?\\s*") String kind) {
        String kindValue() {
            return kind == null || kind.isBlank() ? null : kind.trim();
        }
    }

    @PostMapping("/claims/{claimId}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) @Valid ProofRequest request) {
        return view(service.addProof(sheetId.trim(), user.trim(), claimId, request == null ? null : request.kindValue()));
    }

    @PostMapping("/claims/{claimId}/comments")
    public ClaimView comment(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CommentRequest request) {
        return view(service.comment(sheetId.trim(), user.trim(), claimId, request.text().trim()));
    }

    @PostMapping("/claims/{claimId}/lines")
    public ClaimView addLine(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid LineRequest request) {
        return view(service.addLine(sheetId.trim(), user.trim(), claimId, request.label().trim(),
                request.qtyValue(), request.unitValue()));
    }

    @PostMapping("/claims/{claimId}/remind")
    public ClaimView remind(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.remind(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/hold")
    public ClaimView hold(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.toggleHold(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/waive")
    public ClaimView waive(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.waive(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.resettle(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/defer")
    public ClaimView defer(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid DeferRequest request) {
        return view(service.defer(sheetId.trim(), user.trim(), claimId, request.until()));
    }

    /** Split an amount off the claim: the claim as it is now, then the new claim. */
    @PostMapping("/claims/{claimId}/split")
    public List<ClaimView> split(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SplitRequest request) {
        return views(service.split(sheetId.trim(), user.trim(), claimId, request.amountValue()));
    }

    /** Part-settle the claim: split an amount off it and settle the new claim; the claim as it is now,
     * then the new claim. */
    @PostMapping("/claims/{claimId}/settle-part")
    public List<ClaimView> settlePart(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SplitRequest request) {
        return views(service.partSettle(sheetId.trim(), user.trim(), claimId, request.amountValue()));
    }

    @PostMapping("/claims/{claimId}/redate")
    public ClaimView redate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RedateRequest request) {
        return view(service.redate(sheetId.trim(), user.trim(), claimId, request.date()));
    }

    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReassignRequest request) {
        return view(service.reassign(sheetId.trim(), user.trim(), claimId, request.personId()));
    }

    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestBody @Valid BulkRequest request) {
        if (asOf != null) return new BulkOpenView(0, Set.copyOf(request.claimIds()).size(), List.of());
        ClaimBulkService.Outcome outcome = bulk.open(sheetId.trim(), user.trim(), request.claimIds());
        return new BulkOpenView(outcome.changed().size(), outcome.refused(),
                views(outcome.changed()));
    }

    @PostMapping("/claims/bulk-settle")
    public BulkSettleView bulkSettle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestBody @Valid BulkRequest request) {
        if (asOf != null) return new BulkSettleView(0, Set.copyOf(request.claimIds()).size(), List.of(), null);
        ClaimBulkService.Outcome outcome = bulk.settle(sheetId.trim(), user.trim(), request.claimIds());
        return new BulkSettleView(outcome.changed().size(), outcome.refused(),
                views(outcome.changed()),
                outcome.changed().isEmpty() ? null : outcome.changed().get(0).getSettleBatch());
    }

    @PostMapping("/claims/bulk-reassign")
    public BulkReassignView bulkReassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestBody @Valid BulkReassignRequest request) {
        if (asOf != null) return new BulkReassignView(0, Set.copyOf(request.claimIds()).size(), List.of());
        ClaimBulkService.Outcome outcome = bulk.reassign(sheetId.trim(), user.trim(), request.claimIds(), request.personId());
        return new BulkReassignView(outcome.changed().size(), outcome.refused(),
                views(outcome.changed()));
    }

    @PostMapping("/claims/bulk-redate")
    public BulkRedateView bulkRedate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestBody @Valid BulkRedateRequest request) {
        if (asOf != null) return new BulkRedateView(0, Set.copyOf(request.claimIds()).size(), List.of());
        ClaimBulkService.Outcome outcome = bulk.redate(sheetId.trim(), user.trim(), request.claimIds(), request.date());
        return new BulkRedateView(outcome.changed().size(), outcome.refused(),
                views(outcome.changed()));
    }

    @PostMapping("/claims/bulk-void")
    public BulkVoidView bulkVoid(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestBody @Valid BulkVoidRequest request) {
        if (asOf != null) return new BulkVoidView(0, Set.copyOf(request.claimIds()).size(), List.of());
        ClaimBulkService.Outcome outcome = bulk.voidAll(sheetId.trim(), user.trim(), request.claimIds(), request.reason());
        return new BulkVoidView(outcome.changed().size(), outcome.refused(),
                views(outcome.changed()));
    }

    private ClaimView view(Claim claim) {
        return ClaimView.of(claim, service);
    }

    private List<ClaimView> views(List<Claim> claims) {
        return ClaimView.allOf(claims, service);
    }
}
