package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.period.ClosedPeriodRefused;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * The claim templates on a sheet, and claims added from them; a claim added from a template answers with the
 * person it belongs to. The signed-in user's name comes in the {@code X-User} header.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimTemplateController {

    /** A template to save: a name (blank is refused), an amount with up to two decimals and a note. */
    public record TemplateForm(@Size(max = 1000) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount, @Size(max = 500) String note) {
    }

    /** A template on the sheet; its amount with two decimals. */
    public record TemplateView(String name, String amount, String note) {

        static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getName(), t.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    t.getNote());
        }
    }

    /** The name of the template a claim is added from. */
    public record FromTemplateForm(@NotBlank @Size(max = 1000) String name) {
    }

    public record Refusal(String message) {
    }

    private final ClaimTemplates templates;
    private final ClaimService claims;
    private final PersonService people;
    private final RateService rates;
    private final StatementService statements;

    public ClaimTemplateController(ClaimTemplates templates, ClaimService claims, PersonService people,
            RateService rates, StatementService statements) {
        this.templates = templates;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.statements = statements;
    }

    @GetMapping("/claim-templates")
    public List<TemplateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return templates.list(sheetId.trim()).stream().map(TemplateView::of).toList();
    }

    /** Save a template, replacing the one of its name; answers with every template on the sheet. */
    @PostMapping("/claim-templates")
    public List<TemplateView> save(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody TemplateForm form) {
        String sheet = sheetId.trim();
        templates.save(sheet, user.trim(), form.name(), new BigDecimal(form.amount()), form.note());
        return list(sheet);
    }

    /** Add a DRAFT claim for a person from a template, by the claim form's rules. */
    @PostMapping("/people/{personId}/claims/from-template")
    public PersonView addClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody FromTemplateForm form) {
        String sheet = sheetId.trim();
        long id = templates.addClaim(sheet, user.trim(), personId, form.name());
        return PersonView.of(people.get(sheet, id), claims.forPerson(sheet, id), rates.table(sheet),
                statements.forPerson(sheet, id), people.patrons(sheet, id));
    }

    @ExceptionHandler(ClaimTemplateRefused.class)
    public ResponseEntity<Refusal> refused(ClaimTemplateRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** A claim due by a date inside a closed period: refused, nothing changed. */
    @ExceptionHandler(ClosedPeriodRefused.class)
    public ResponseEntity<Refusal> closed(ClosedPeriodRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user saved a new template of the same name at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
