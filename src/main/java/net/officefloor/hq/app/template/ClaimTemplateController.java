package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimTemplateController {

    /** A template as the screen shows it; the amount always carries two decimals. */
    public record TemplateView(Long id, String name, String amount, String note) {
        static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getAmount().toPlainString(), t.getNote());
        }
    }

    public record TemplateForm(@NotNull @Size(max = 140) String name, @NotNull BigDecimal amount,
            @Size(max = 500) String note) {
    }

    public record FromTemplateForm(@NotNull @Size(max = 140) String template) {
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    /** The sheet's claim templates, by name. */
    @GetMapping("/claim-templates")
    public List<TemplateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(TemplateView::of).toList();
    }

    /** Save a template; saving a name again replaces it. */
    @PostMapping("/claim-templates")
    public TemplateView save(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody TemplateForm form, @SessionAttribute(SessionController.USER) String user) {
        return TemplateView.of(service.save(user, sheetId, form.name(), form.amount(), form.note()));
    }

    /** Add a draft claim for the person from a template, as the claim form adds one with no date. */
    @PostMapping("/people/{personId}/claims/from-template")
    public ClaimView addClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @Valid @RequestBody FromTemplateForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return ClaimView.of(service.addClaim(user, sheetId, personId, form.template()));
    }
}
