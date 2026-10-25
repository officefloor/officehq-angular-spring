package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
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

    /** What the screen shows of a template; the amount always has two decimals. */
    public record TemplateView(long id, String name, String amount, String note) {
        static TemplateView of(ClaimTemplate template) {
            return new TemplateView(template.getId(), template.getName(), template.getAmount().toPlainString(),
                    template.getNote());
        }
    }

    /** A template, as typed on the form; the amount is checked as the claim form checks it. */
    public record TemplateForm(@Size(max = 200) String name, @Size(max = 30) String amount,
            @Size(max = 1000) String note) {
    }

    /** The template a claim is made from, by name. */
    public record FromTemplateForm(@NotBlank @Size(max = 200) String template) {
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    /** The sheet's claim templates by name. */
    @GetMapping("/templates")
    public List<TemplateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(TemplateView::of).toList();
    }

    /** Save a template, replacing the one of the same name. */
    @PutMapping("/templates")
    public TemplateView save(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid TemplateForm form) {
        return TemplateView.of(service.save(sheetId.trim(), SignedInUser.name(user), form.name(), form.amount(),
                form.note()));
    }

    /** Add a draft claim to a person from a template. */
    @PostMapping("/people/{personId}/claims/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView addClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid FromTemplateForm form) {
        return ClaimView.of(service.addClaim(sheetId.trim(), SignedInUser.name(user), personId, form.template()));
    }
}
