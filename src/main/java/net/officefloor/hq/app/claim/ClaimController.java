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
import net.officefloor.hq.app.SignedInUser;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** What the screen shows of a claim; the amount always has two decimals. */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status) {
        static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus());
        }
    }

    /** A claim's fields, as entered on the form; no date means the sheet's today. */
    public record ClaimForm(
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note) {
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /** The sheet's claims, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimView::of).toList();
    }

    /** Add a claim to a person. */
    @PostMapping("/people/{personId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.add(sheetId.trim(), SignedInUser.name(user), personId,
                form.amount(), form.date(), form.note()));
    }

    /** Edit a claim. */
    @PutMapping("/claims/{id}")
    public ClaimView update(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ClaimForm form) {
        return ClaimView.of(service.update(sheetId.trim(), SignedInUser.name(user), id,
                form.amount(), form.date(), form.note()));
    }

    /** Open a draft claim. */
    @PostMapping("/claims/{id}/open")
    public ClaimView open(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.OPEN));
    }

    /** Settle an open claim. */
    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.SETTLED));
    }

    /** Void a draft or open claim. */
    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return ClaimView.of(service.changeStatus(sheetId.trim(), SignedInUser.name(user), id, ClaimStatus.VOID));
    }
}
