package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The claim templates on a sheet, and the claims added from them. The signed-in user's name travels in
 * the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimTemplateController {

    /** A claim template: its id, name, amount (as text with two decimals) and note. */
    public record TemplateView(long id, String name, String amount, String note) {
        static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getId(), t.getName(),
                    t.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), t.getNote());
        }
    }

    /** A template saved: its name (trimmed, not blank), an amount the claim form takes (up to two
     * decimals; negative for a correction) and a note (may be empty). */
    public record TemplateRequest(
            @NotNull @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            @Size(max = 500) String note) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    /** The template a claim is added from, by name. */
    public record FromTemplateRequest(@NotNull @Size(max = 200) String name) {
    }

    private final ClaimTemplateService service;
    private final ClaimService claims;

    public ClaimTemplateController(ClaimTemplateService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    @GetMapping("/templates")
    public List<TemplateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.templates(sheetId.trim()).stream().map(TemplateView::of).toList();
    }

    @PostMapping("/templates")
    public List<TemplateView> save(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TemplateRequest request) {
        String sheet = sheetId.trim();
        service.save(sheet, user.trim(), request.name().trim(), request.amountValue(), request.note());
        return list(sheet);
    }

    @PostMapping("/people/{personId}/claims/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView claim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FromTemplateRequest request) {
        return ClaimView.of(service.claim(sheetId.trim(), user.trim(), personId, request.name().trim()), claims);
    }
}
