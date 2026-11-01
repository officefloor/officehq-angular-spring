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
import org.springframework.validation.annotation.Validated;
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
     * correction has the number of the claim it corrects (null for any other claim).
     */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().setScale(2).toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().setScale(2).toPlainString(),
                    claim.getCorrects());
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
            @Size(max = 220) @Pattern(regexp = "\\S+") String corrects) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }

        String correctsValue() {
            return corrects == null || corrects.isEmpty() ? null : corrects;
        }

        String noteValue() {
            return note == null ? "" : note.strip();
        }
    }

    /** Why a steward rejects a PENDING claim. */
    public record RejectRequest(@NotBlank @Size(max = 200) @Pattern(regexp = "\\S+( \\S+)*") String reason) {}

    /** The person on the sheet a claim is moved to. */
    public record ReassignRequest(@NotNull Long personId) {}

    /** The claims selected for a bulk change, by id. */
    public record BulkRequest(@NotNull @Size(max = 1000) Set<@NotNull Long> ids) {}

    /** How many of the selected claims a bulk open opened, and how many it refused. */
    public record BulkOpenView(int opened, int refused) {}

    private final ClaimService service;
    private final ClaimBulkService bulk;

    public ClaimController(ClaimService service, ClaimBulkService bulk) {
        this.service = service;
        this.bulk = bulk;
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
                request.correctsValue()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView edit(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.edit(sheetId, user, id, request.amountValue(), request.date(), request.noteValue(),
                request.correctsValue()));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/open")
    public BulkOpenView bulkOpen(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkRequest request) {
        ClaimBulkService.Result result = bulk.open(sheetId, user, request.ids());
        return new BulkOpenView(result.done(), result.refused());
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
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId, user, id, ClaimStatus.VOID));
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

    @PostMapping("/claims/{id}/reassign")
    public ClaimView reassign(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ReassignRequest request) {
        return ClaimView.of(service.reassign(sheetId, user, id, request.personId()));
    }
}
