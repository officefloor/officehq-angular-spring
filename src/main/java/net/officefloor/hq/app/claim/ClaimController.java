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

/** The people's claims on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** A claim as shown: the amount with two decimals, the date as yyyy-mm-dd. */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            Long number) {

        static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber());
        }
    }

    /** An amount of 0.00 or more with up to two decimals; a missing date takes the sheet's today. */
    public record ClaimRequest(
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate date,
            @Size(max = 1000) String note) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /** All the sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.date(),
                request.note()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.update(sheetId.trim(), user.trim(), id, request.amountValue(), request.date(),
                request.note()));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), id, ClaimStatus.OPEN));
    }

    /** Settle an OPEN claim. */
    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), id, ClaimStatus.SETTLED));
    }

    /** Void a DRAFT or OPEN claim. */
    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), id, ClaimStatus.VOID));
    }
}
