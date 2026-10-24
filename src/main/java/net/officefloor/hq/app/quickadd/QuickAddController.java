package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The quick-add bar of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/quick-add")
@Validated
public class QuickAddController {

    /** A person's name, as on the person form, and a claim's amount, as on the claim form. */
    public record QuickAddRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    /** The person the claim was added for (perhaps added just now) and the claim. */
    public record QuickAddView(PersonView person, ClaimView claim) {
    }

    private final QuickAddService service;
    private final PersonService personService;
    private final ClaimService claimService;

    public QuickAddController(QuickAddService service, PersonService personService, ClaimService claimService) {
        this.service = service;
        this.personService = personService;
        this.claimService = claimService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid QuickAddRequest request) {
        QuickAddService.Added added = service.add(sheetId.trim(), user.trim(), request.name(), request.amountValue());
        PersonView person = PersonView.of(added.person(), personService.today(sheetId.trim()),
                personService.clashes(added.person()));
        return new QuickAddView(person, ClaimView.of(added.claim(), claimService));
    }
}
