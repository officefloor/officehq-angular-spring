package net.officefloor.hq.app.claim;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.setting.PermissionRefusedException;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.rollup.RollupService;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** A claim as the screen shows it; the amount and the due fixed on settling (null when none
     *  was) are strings with two decimals; openedBy is null for a claim never opened; stated tells
     *  whether a statement bound the claim, statedDue the figure it bound it to (null when none);
     *  corrects is the number of the claim a correction corrects (null when it is not one); roundsBy
     *  is the date its live figures round by against the sheet's policy date (its own date, or for a
     *  correction the date of the claim it corrects); dueBy is the date it is due by, fixed when it was
     *  added; log is its status log, oldest first, one line per status it has had; deferredUntil is
     *  the date it was deferred until (null when it never was); proofs is how many proofs were added
     *  to it. */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, boolean stated,
            String statedDue, String corrects, LocalDate roundsBy, LocalDate dueBy, List<String> log,
            LocalDate deferredUntil, int proofs) {
        public static ClaimView of(Claim claim, LocalDate roundsBy, List<String> log) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() != null ? claim.getSettledDue().toPlainString() : null,
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() != null ? claim.getStatedDue().toPlainString() : null,
                    claim.getCorrects(), roundsBy, claim.getDueBy(), log, claim.getDeferredUntil(),
                    claim.getProofs());
        }
    }

    /** A claim's fields: an amount with up to two decimals (a negative one is a correction), a date
     *  (blank takes the sheet's today), a note (may be empty) and, for a correction, the number of
     *  the claim it corrects. */
    public record ClaimRequest(
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 2000) String note,
            @Size(max = 255) String corrects) {
    }

    /** The date an OPEN claim is deferred until. */
    public record DeferRequest(@NotNull LocalDate until) {
    }

    /** Why a steward rejects a PENDING claim. */
    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** The person on the sheet a claim moves to. */
    public record ReassignRequest(@NotNull Long toPersonId) {
    }

    /** The claims selected to open together. */
    public record BulkOpenRequest(@NotNull @Size(max = 10000) Set<@NotNull Long> claimIds) {
    }

    /** What a bulk open did: the claims it opened, in the order it numbered them, and how many of the
     *  selected claims it refused. */
    public record BulkOpenView(List<ClaimView> opened, int refused) {
    }

    /** The claims selected to settle together. */
    public record BulkSettleRequest(@NotNull @Size(max = 10000) Set<@NotNull Long> claimIds) {
    }

    /** What a bulk settle did: the claims it settled (SETTLED, or PENDING a steward's approval), in
     *  list order, and how many of the selected claims it refused. */
    public record BulkSettleView(List<ClaimView> settled, int refused) {
    }

    private final ClaimService service;
    private final RollupService rollups;
    private final BulkOpenService bulkOpen;
    private final BulkSettleService bulkSettle;

    public ClaimController(ClaimService service, RollupService rollups, BulkOpenService bulkOpen,
            BulkSettleService bulkSettle) {
        this.service = service;
        this.rollups = rollups;
        this.bulkOpen = bulkOpen;
        this.bulkSettle = bulkSettle;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        String sheet = sheetId.trim();
        Map<Long, List<String>> logs = service.logsOf(sheet);
        return service.list(sheet).stream()
                .map(claim -> ClaimView.of(claim, service.roundsBy(sheet, claim), logs.getOrDefault(claim.getId(), List.of())))
                .toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated ClaimRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.add(sheet, by, personId, request.amount(),
                request.date(), request.note(), request.corrects())));
    }

    /** Change a DRAFT claim's amount, date and note. */
    @PutMapping("/people/{personId}/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated ClaimRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.update(sheet, by, personId, id,
                request.amount(), request.date(), request.note(), request.corrects())));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/people/{personId}/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.OPEN);
    }

    /** Open the selected claims that can be opened, numbered in list order; the rest are refused
     *  and left as they are. */
    @PostMapping("/claims/open")
    public BulkOpenView openAll(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated BulkOpenRequest request) {
        String sheet = sheetId.trim();
        BulkOpenService.Result result = bulkOpen.open(sheet, SignedInUser.decode(user), request.claimIds());
        return new BulkOpenView(result.opened().stream().map(claim -> view(sheet, claim)).toList(), result.refused());
    }

    /** Settle an OPEN claim; the user who opened it cannot. A clerk settling one whose due is above
     *  the clerk limit leaves it PENDING a steward's approval. */
    @PostMapping("/people/{personId}/claims/{id}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.SETTLED);
    }

    /** Settle the selected claims that can be settled, in list order, each by the rules of settling
     *  one; the rest are refused and left as they are. */
    @PostMapping("/claims/settle")
    public BulkSettleView settleAll(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated BulkSettleRequest request) {
        String sheet = sheetId.trim();
        BulkSettleService.Result result = bulkSettle.settle(sheet, SignedInUser.decode(user), request.claimIds());
        return new BulkSettleView(result.settled().stream().map(claim -> view(sheet, claim)).toList(), result.refused());
    }

    /** Approve a PENDING claim: a steward other than whoever opened it settles it. */
    @PostMapping("/people/{personId}/claims/{id}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.approve(sheet, by, personId, id)));
    }

    /** Reject a PENDING claim with a reason: a steward sends it back to OPEN. */
    @PostMapping("/people/{personId}/claims/{id}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated RejectRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.reject(sheet, by, personId, id, request.reason())));
    }

    /** Re-settle a SETTLED claim at the figure it would have today: a steward only. */
    @PostMapping("/people/{personId}/claims/{id}/resettle")
    public ClaimView resettle(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.resettle(sheet, by, personId, id)));
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person: a steward only. */
    @PostMapping("/people/{personId}/claims/{id}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated ReassignRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by,
                () -> service.reassign(sheet, by, personId, id, request.toPersonId())));
    }

    /** Defer an OPEN claim until a date after the sheet's today. */
    @PostMapping("/people/{personId}/claims/{id}/defer")
    public ClaimView defer(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated DeferRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.defer(sheet, by, personId, id, request.until())));
    }

    /** Add a proof to a claim that is not SETTLED or VOID. */
    @PostMapping("/people/{personId}/claims/{id}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, service.addProof(sheet, by, personId, id));
    }

    /** Void a DRAFT, OPEN or PENDING claim. */
    @PostMapping("/people/{personId}/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.VOID);
    }

    private ClaimView changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(sheet, rollups.track(sheet, by, () -> service.changeStatus(sheet, by, personId, id, to)));
    }

    private ClaimView view(String sheet, Claim claim) {
        return ClaimView.of(claim, service.roundsBy(sheet, claim), service.logOf(claim.getId()));
    }

    /** A status change the claim's status does not allow, an edit of a claim that is not a DRAFT,
     *  any change to a claim dated in a closed period, a claim dated after its person retired, or a
     *  correction that does not name a SETTLED claim of its person, a move the claim does not allow,
     *  a deferral the claim does not allow, a proof added to a SETTLED or VOID claim, or settling or
     *  approving a claim above the proof threshold with no proofs, is refused. */
    @ExceptionHandler({StatusChangeRefusedException.class, ClaimNotEditableException.class, CorrectionRefusedException.class,
            PeriodClosedException.class, PersonRetiredException.class, MoveRefusedException.class,
            DeferRefusedException.class, ProofRefusedException.class, ProofRequiredException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** The user who opened a claim cannot settle or approve it, only a steward approves or rejects a
     *  PENDING claim or re-settles a SETTLED one, and only a role the sheet's permission matrix allows
     *  opens, settles, voids or moves a claim to another person. */
    @ExceptionHandler({OwnClaimSettleRefusedException.class, ApprovalRefusedException.class, ReassignRefusedException.class,
            ResettleRefusedException.class, PermissionRefusedException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void ownClaim() {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    /** An amount that is not a number or a date that is not a real yyyy-mm-dd date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
