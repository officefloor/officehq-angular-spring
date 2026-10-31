package net.officefloor.hq.app.claimtemplate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The claim templates on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimTemplateController {

    /** A template as the screen shows it. */
    public record TemplateView(long id, String name, String amount, String note) {

        public static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getAmount().toPlainString(), t.getNote());
        }
    }

    /** A template as the form submits it, its amount under the claim form's rules for one that corrects none. */
    public record TemplateForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 1000) String note) {
    }

    /** The template a claim is to be added from, by name. */
    public record FromTemplate(@NotBlank @Size(max = 200) String template) {
    }

    private final ClaimTemplateService templates;

    public ClaimTemplateController(ClaimTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping("/templates")
    public List<TemplateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return templates.list(sheetId).stream().map(TemplateView::of).toList();
    }

    @PostMapping("/templates")
    public TemplateView save(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TemplateForm request) {
        return TemplateView.of(templates.save(sheetId, user, request.name(), request.amount(), request.note()));
    }

    @PostMapping("/people/{personId}/claims/from-template")
    public ClaimView addClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FromTemplate request) {
        return ClaimView.of(templates.addClaim(sheetId, user, personId, request.template()));
    }
}
