package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
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

/** The claim templates of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimTemplateController {

    /** A claim template as shown: the amount with two decimals. */
    public record ClaimTemplateView(Long id, String name, String amount, String note) {

        static ClaimTemplateView of(ClaimTemplate t) {
            return new ClaimTemplateView(t.getId(), t.getName(),
                    t.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), t.getNote());
        }
    }

    /**
     * The template: its name, not blank once trimmed; an amount the claim form takes for a claim that corrects
     * nothing (up to two decimals, not negative); a note, blank for none.
     */
    public record ClaimTemplateRequest(
            @NotNull @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            @Size(max = 1000) String note) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }

        String noteValue() {
            return note == null ? "" : note.trim();
        }
    }

    /** The name of the template to add a claim from. */
    public record FromTemplateRequest(@NotNull @NotBlank @Size(max = 200) String name) {
    }

    private final ClaimTemplateService service;
    private final ClaimService claims;

    public ClaimTemplateController(ClaimTemplateService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    /** The sheet's claim templates, by name. */
    @GetMapping("/templates")
    public List<ClaimTemplateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(ClaimTemplateView::of).toList();
    }

    /** Save a template, replacing the one of the same name. */
    @PutMapping("/templates")
    public ClaimTemplateView save(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ClaimTemplateRequest request) {
        return ClaimTemplateView.of(service.save(sheetId.trim(), user.trim(), request.name().trim(),
                request.amountValue(), request.noteValue()));
    }

    /** Add a DRAFT claim for the person with the template's amount and note now. */
    @PostMapping("/people/{personId}/claims/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView addClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FromTemplateRequest request) {
        Claim claim = service.addClaim(sheetId.trim(), user.trim(), personId, request.name());
        return ClaimView.of(claim, claims.logOf(claim));
    }
}
