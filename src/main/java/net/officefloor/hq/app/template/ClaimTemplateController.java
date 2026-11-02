package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimTemplateController {

    /** A claim template as the screen shows it: its amount as text with two decimals. */
    public record TemplateView(long id, String name, String amount, String note) {
        static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getAmount().setScale(2).toPlainString(), t.getNote());
        }
    }

    /** The template form: a required name, an amount the claim form would take, and a note that may
     *  be empty. */
    public record TemplateForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*-?\\d{1,12}(\\.\\d{1,2})?\\s*") String amount,
            @Size(max = 500) String note) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    /** The template a claim is made from, by name. */
    public record FromTemplateForm(@NotBlank @Size(max = 200) String name) {
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    @GetMapping("/claim-templates")
    public List<TemplateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(TemplateView::of).toList();
    }

    @PutMapping("/claim-templates")
    public TemplateView save(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TemplateForm form) {
        return TemplateView.of(service.save(sheetId.trim(), user.trim(), form.name(), form.amountValue(), form.note()));
    }

    @PostMapping("/people/{personId}/claims/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView addClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FromTemplateForm form) {
        return ClaimView.of(service.addClaim(sheetId.trim(), user.trim(), personId, form.name()));
    }
}
