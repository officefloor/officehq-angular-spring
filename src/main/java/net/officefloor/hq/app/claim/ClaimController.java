package net.officefloor.hq.app.claim;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.period.PeriodClosedException;
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
     *  whether a statement bound the claim, statedDue the figure it bound it to (null when none). */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note,
            ClaimStatus status, Long number, String settledDue, String openedBy, boolean stated,
            String statedDue) {
        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() != null ? claim.getSettledDue().toPlainString() : null,
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() != null ? claim.getStatedDue().toPlainString() : null);
        }
    }

    /** A claim's fields: an amount of 0.00 or more with up to two decimals, a date (blank takes
     *  the sheet's today) and a note (may be empty). */
    public record ClaimRequest(
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 2000) String note) {
    }

    private final ClaimService service;
    private final RollupService rollups;

    public ClaimController(ClaimService service, RollupService rollups) {
        this.service = service;
        this.rollups = rollups;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated ClaimRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return ClaimView.of(rollups.track(sheet, by, () -> service.add(sheet, by, personId, request.amount(),
                request.date(), request.note())));
    }

    /** Change a DRAFT claim's amount, date and note. */
    @PutMapping("/people/{personId}/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated ClaimRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return ClaimView.of(rollups.track(sheet, by, () -> service.update(sheet, by, personId, id,
                request.amount(), request.date(), request.note())));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/people/{personId}/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.OPEN);
    }

    /** Settle an OPEN claim; the user who opened it cannot. */
    @PostMapping("/people/{personId}/claims/{id}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.SETTLED);
    }

    /** Void a DRAFT or OPEN claim. */
    @PostMapping("/people/{personId}/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @PathVariable Long id, @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return changeStatus(sheetId, user, personId, id, ClaimStatus.VOID);
    }

    private ClaimView changeStatus(String sheetId, String user, Long personId, Long id, ClaimStatus to) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return ClaimView.of(rollups.track(sheet, by, () -> service.changeStatus(sheet, by, personId, id, to)));
    }

    /** A status change the claim's status does not allow, an edit of a claim that is not a DRAFT,
     *  or any change to a claim dated in a closed period, is refused. */
    @ExceptionHandler({StatusChangeRefusedException.class, ClaimNotEditableException.class,
            PeriodClosedException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** The user who opened a claim cannot settle it. */
    @ExceptionHandler(OwnClaimSettleRefusedException.class)
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
