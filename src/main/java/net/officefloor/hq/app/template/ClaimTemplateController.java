package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimTemplateController {

    /** What the screen is told about a template: its name, its amount with two decimals, and its note. */
    public record TemplateView(long id, String name, String amount, String note) {

        static TemplateView of(ClaimTemplate template) {
            return new TemplateView(template.getId(), template.getName(), template.getAmount().toPlainString(),
                    template.getNote());
        }
    }

    /** A template as entered on the form: a name, an amount as the claim form takes it, and a note. */
    public record TemplateForm(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            @Size(max = 500) String note) {
    }

    /** The template a claim is made from, by name. */
    public record FromTemplateForm(@NotBlank @Size(max = 100) String name) {
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    /** The sheet's templates by name. */
    @GetMapping("/templates")
    public List<TemplateView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(TemplateView::of).toList();
    }

    /** Save a template; a name saved again replaces it. */
    @PostMapping("/templates")
    public TemplateView save(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TemplateForm form) {
        return TemplateView.of(service.save(sheetId, SignedIn.name(user), form.name(), new BigDecimal(form.amount()),
                form.note()));
    }

    /** Add a DRAFT claim for a person from a template, as the template is now. */
    @PostMapping("/people/{personId}/claims/from-template")
    public ClaimView claimFrom(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FromTemplateForm form) {
        return ClaimView.of(service.claimFrom(sheetId, SignedIn.name(user), personId, form.name()));
    }

    /** Another user saved the same new name between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
