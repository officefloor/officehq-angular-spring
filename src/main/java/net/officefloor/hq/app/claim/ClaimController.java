package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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

/** The people's claims on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** A claim as the screen shows it: the amount with two decimals, the date YYYY-MM-DD, no number unless opened,
     *  the due fixed when it was settled (none while its due is live)
     *  and who opened it (none while a draft). */
    public record ClaimView(long id, long personId, String amount, String date, String note,
            ClaimStatus status, Integer number, String settledDue, String openedBy) {

        static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate().toString(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy());
        }
    }

    /** A claim's fields; a missing date takes the sheet's today, a missing note is empty. */
    public record ClaimRequest(
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note) {

        String noteOrEmpty() {
            return note == null ? "" : note.trim();
        }
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    public ClaimView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.add(sheetId.trim(), user.trim(), personId, request.amount(), request.date(),
                request.noteOrEmpty()));
    }

    /** Change a claim's amount, date and note. */
    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.update(sheetId.trim(), user.trim(), claimId, request.amount(), request.date(),
                request.noteOrEmpty()));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.OPEN));
    }

    /** Settle an OPEN claim. */
    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.SETTLED));
    }

    /** Void a DRAFT or OPEN claim. */
    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), user.trim(), claimId, ClaimStatus.VOID));
    }

    /** A settle by the user who opened the claim is refused with the reason, for the screen to show. */
    @ExceptionHandler(ClaimSettlerException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> settlerRefused(ClaimSettlerException e) {
        return Map.of("message", e.getMessage());
    }
}
