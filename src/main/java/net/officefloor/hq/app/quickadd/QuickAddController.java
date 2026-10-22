package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/quick-add")
@Validated
public class QuickAddController {

    /** The quick-add bar's values: a person's name, as on the person form, and an amount, as on the claim form. */
    public record QuickAddForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {
    }

    /** What the screen is told: the claim added, its person, and whether that person was added for it. */
    public record QuickAddView(PersonView person, boolean personAdded, ClaimView claim) {
    }

    private final QuickAddService service;

    public QuickAddController(QuickAddService service) {
        this.service = service;
    }

    /** Add a DRAFT claim for the named person, adding them first when the name is not on the sheet. */
    @PostMapping
    public QuickAddView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid QuickAddForm form) {
        QuickAddService.Added added = service.add(sheetId, SignedIn.name(user), form.name(),
                new BigDecimal(form.amount()));
        return new QuickAddView(PersonView.of(added.person()), added.personAdded(), ClaimView.of(added.claim()));
    }

    /** Another user took the name between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
