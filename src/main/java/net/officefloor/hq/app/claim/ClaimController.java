package net.officefloor.hq.app.claim;

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
     * the figure it is bound to (null when unbound or bound to no figure), and the date it was last reminded
     * (null when never), are sent as text with two
     * decimals so they are never rounded on the way. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, LocalDate dueBy, String note,
            ClaimStatus status, String number, String settledDue,
            String openedBy, boolean stated, String statedDue, String corrects, List<String> log,
            LocalDate deferredUntil, int proofs, String splitFrom, boolean lateEntry, LocalDate remindedOn) {
        /** The claims as views, each with its status log, in the order given. */
        public static List<ClaimView> allOf(List<Claim> claims, ClaimService service) {
            Map<Long, List<ClaimLog>> logs = service.logsOf(claims);
            return claims.stream().map(c -> of(c, logs.getOrDefault(c.getId(), List.of()))).toList();
        }

        /** The claim as a view, with its status log. */
        public static ClaimView of(Claim claim, ClaimService service) {
            return allOf(List.of(claim), service).get(0);
        }

        static ClaimView of(Claim claim, List<ClaimLog> log) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    claim.getDate(), claim.getDueBy(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    text(claim.getSettledDue()), claim.getOpenedBy(), claim.isStated(), text(claim.getStatedDue()),
                    claim.getCorrects(), log.stream().map(ClaimView::line).toList(),
                    claim.getDeferredUntil(), claim.getProofs(), claim.getSplitFrom(),
                    claim.isLateEntry(), claim.getRemindedOn());
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
     * when left out), note (may be empty) and, on a correction, the number of the claim it corrects. */
    public record ClaimRequest(
            @NotNull @Pattern(regexp = "\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate date,
            @Size(max = 500) String note,
            @Size(max = 120) String corrects) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }

        /** The number named, trimmed; null when none is. */
        String correctsValue() {
            return corrects == null || corrects.isBlank() ? null : corrects.trim();
        }
    }

    /** Why a steward rejects a PENDING claim: one word, as it is written into the audit. */
    public record RejectRequest(@NotNull @Pattern(regexp = "\\s*\\S{1,200}\\s*") String reason) {
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

    /** How a bulk settle went: how many claims it settled (or left PENDING) and refused, and those claims. */
    public record BulkSettleView(int settled, int refused, List<ClaimView> claims) {
    }

    private final ClaimService service;
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
            return then.claims().stream()
                    .map(c -> ClaimView.of(c, then.logged(logs.getOrDefault(c.getId(), List.of())))).toList();
        }
        return views(service.list(sheetId.trim()));
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return view(service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.date(),
                request.note(), request.correctsValue()));
    }

    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return view(service.update(sheetId.trim(), user.trim(), claimId, request.amountValue(), request.date(),
                request.note(), request.correctsValue()));
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
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID));
    }

    @PostMapping("/claims/{claimId}/proof")
    public ClaimView addProof(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.addProof(sheetId.trim(), user.trim(), claimId));
    }

    @PostMapping("/claims/{claimId}/remind")
    public ClaimView remind(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(service.remind(sheetId.trim(), user.trim(), claimId));
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
        if (asOf != null) return new BulkSettleView(0, Set.copyOf(request.claimIds()).size(), List.of());
        ClaimBulkService.Outcome outcome = bulk.settle(sheetId.trim(), user.trim(), request.claimIds());
        return new BulkSettleView(outcome.changed().size(), outcome.refused(),
                views(outcome.changed()));
    }

    private ClaimView view(Claim claim) {
        return ClaimView.of(claim, service);
    }

    private List<ClaimView> views(List<Claim> claims) {
        return ClaimView.allOf(claims, service);
    }
}
