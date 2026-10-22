package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
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
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /**
     * What the screen is told about a claim; the amount with two decimals, and the due fixed when it
     * was settled (null when not fixed, so its due is worked out from the current rate), and who
     * opened it (null until opened), and whether it is bound by its person's last statement, with the
     * figure it was bound to (null when unbound, or when it was n/a), and the number of the claim it
     * corrects (null unless it is a correction).
     */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note, ClaimStatus status,
            Integer number, String settledDue, String openedBy, boolean stated, String statedDue, Integer corrects) {

        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() == null ? null : claim.getStatedDue().toPlainString(),
                    claim.getCorrects());
        }
    }

    /**
     * A claim's values as entered on the form: the amount is required, with up to two decimals, and
     * below 0.00 only on a correction, which names the number of the claim it corrects; no date means
     * the sheet's today (or, on an edit, the claim's own); the note may be empty.
     */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,12}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 500) String note,
            @Positive Integer corrects) {
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    /** Add a claim for a person. */
    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.add(sheetId, SignedIn.name(user), personId, new BigDecimal(form.amount()), form.date(), form.note(), form.corrects()));
    }

    /** Change a claim's amount, date and note. */
    @PutMapping("/claims/{claimId}")
    public ClaimView edit(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.edit(sheetId, SignedIn.name(user), claimId, new BigDecimal(form.amount()), form.date(), form.note(), form.corrects()));
    }

    /** Open a DRAFT claim. */
    @PostMapping("/claims/{claimId}/open")
    public ClaimView open(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.move(sheetId, SignedIn.name(user), claimId, ClaimStatus.OPEN));
    }

    /** Settle an OPEN claim; refused for the user who opened it. */
    @PostMapping("/claims/{claimId}/settle")
    public ClaimView settle(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.move(sheetId, SignedIn.name(user), claimId, ClaimStatus.SETTLED));
    }

    /** Void a DRAFT or OPEN claim. */
    @PostMapping("/claims/{claimId}/void")
    public ClaimView voidClaim(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long claimId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return ClaimView.of(service.move(sheetId, SignedIn.name(user), claimId, ClaimStatus.VOID));
    }
}
