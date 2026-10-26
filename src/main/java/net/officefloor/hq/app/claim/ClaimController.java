package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** A claim as the screen shows it; the amount always carries two decimals. */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note) {
        static ClaimView of(Claim c) {
            return new ClaimView(c.getId(), c.getPersonId(), c.getAmount().toPlainString(), c.getDate(), c.getNote());
        }
    }

    /** A claim's fields as the form submits them; a missing date takes the sheet's today. */
    public record ClaimForm(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            LocalDate date, @Size(max = 500) String note) {
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    /** All the claims on the sheet, newest first. */
    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.add(sheetId, personId, form.amount(), form.date(), form.note()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.update(sheetId, id, form.amount(), form.date(), form.note()));
    }
}
