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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people's claims on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** A claim as the screen shows it: the amount with two decimals, the date YYYY-MM-DD. */
    public record ClaimView(long id, long personId, String amount, String date, String note) {

        static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate().toString(), claim.getNote());
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
        return ClaimView.of(service.add(sheetId.trim(), personId, request.amount(), request.date(),
                request.noteOrEmpty()));
    }

    /** Change a claim's amount, date and note. */
    @PutMapping("/claims/{claimId}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long claimId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimRequest request) {
        return ClaimView.of(service.update(sheetId.trim(), claimId, request.amount(), request.date(),
                request.noteOrEmpty()));
    }
}
