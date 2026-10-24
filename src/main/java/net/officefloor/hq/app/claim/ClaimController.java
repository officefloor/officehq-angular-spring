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

    /** A claim, with who opened it (null until opened) and whether it is bound to the figure it had
     * at its person's last statement; its amount, what was due on it when settled (null otherwise) and
     * the figure it is bound to (null when unbound or bound to no figure) are sent as text with two
     * decimals so they are never rounded on the way. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue,
            String openedBy, boolean stated, String statedDue, String corrects) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    text(claim.getSettledDue()), claim.getOpenedBy(), claim.isStated(), text(claim.getStatedDue()),
                    claim.getCorrects());
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

    /** The claims a bulk change is asked of, by id. */
    public record BulkRequest(@NotNull @Size(max = 1000) List<@NotNull Long> claimIds) {
    }

    /** How a bulk open went: how many claims it opened and refused, and the claims it opened. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    private final ClaimService service;
    private final ClaimBulkService bulk;

    public ClaimController(ClaimService service, ClaimBulkService bulk) {
        this.service = service;
        this.bulk = bulk;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.date(),
                request.note(), request.correctsValue()));
    }

    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.update(sheetId.trim(), user.trim(), claimId, request.amountValue(), request.date(),
                request.note(), request.correctsValue()));
    }

    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID));
    }

    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid BulkRequest request) {
        ClaimBulkService.Outcome outcome = bulk.open(sheetId.trim(), user.trim(), request.claimIds());
        return new BulkOpenView(outcome.changed().size(), outcome.refused(),
                outcome.changed().stream().map(ClaimView::of).toList());
    }
}
