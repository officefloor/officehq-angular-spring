package net.officefloor.hq.app.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

/** Claims on a sheet. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * The amount is sent as text with exactly two decimals, so it is never rounded as a float; so is
     * the due fixed when the claim was settled (null if it is not settled or had no rate then).
     */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy, String corrects) {
        static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.getCorrects());
        }
    }

    /**
     * The amount has at most two decimals; a negative one is a correction, which names the number of
     * the settled claim it corrects. A missing date is the sheet's today.
     */
    public record ClaimRequest(
            @NotNull @Pattern(regexp = "-?\\d{1,10}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 500) String note,
            @Size(max = 120) String corrects) {
    }

    /** The quick-add bar: a name (stored trimmed) and an amount, with the rules of the person and claim forms. */
    public record QuickAddRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount) {
    }

    /** The claims selected to be opened together, by id. */
    public record BulkOpenRequest(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    private final ClaimService service;
    private final QuickAddService quickAdd;

    public ClaimController(ClaimService service, QuickAddService quickAdd) {
        this.service = service;
        this.quickAdd = quickAdd;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated ClaimRequest request) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId,
                new BigDecimal(request.amount()), request.date(), request.note(), blankAsNull(request.corrects())));
    }

    @PostMapping("/quick-add")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView quickAdd(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated QuickAddRequest request) {
        return ClaimView.of(quickAdd.add(sheetId.trim(), user.trim(), request.name(), new BigDecimal(request.amount())));
    }

    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated ClaimRequest request) {
        return ClaimView.of(service.update(sheetId.trim(), user.trim(), claimId,
                new BigDecimal(request.amount()), request.date(), request.note(), blankAsNull(request.corrects())));
    }

    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID));
    }

    @PostMapping("/claims/{claimId}/approve")
    public ClaimView approve(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.approve(sheetId.trim(), user.trim(), claimId));
    }

    /** The reason a steward rejects a PENDING claim for, stored trimmed. */
    public record RejectRequest(@NotBlank @Size(max = 200) String reason) {
    }

    @PostMapping("/claims/{claimId}/reject")
    public ClaimView reject(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated RejectRequest request) {
        return ClaimView.of(service.reject(sheetId.trim(), user.trim(), claimId, request.reason().trim()));
    }

    /** The person on the sheet a steward moves a claim to, by id. */
    public record ReassignRequest(@NotNull Long personId) {
    }

    @PostMapping("/claims/{claimId}/reassign")
    public ClaimView reassign(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated ReassignRequest request) {
        return ClaimView.of(service.reassign(sheetId.trim(), user.trim(), claimId, request.personId()));
    }

    /** A corrected number as sent, trimmed; none when blank. */
    private static String blankAsNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    @PostMapping("/claims/bulk-open")
    public ClaimService.BulkResult bulkOpen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated BulkOpenRequest request) {
        return service.bulkOpen(sheetId.trim(), user.trim(), request.claimIds());
    }
}
