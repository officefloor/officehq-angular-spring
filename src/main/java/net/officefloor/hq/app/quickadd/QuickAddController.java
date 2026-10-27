package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/quick-add")
public class QuickAddController {

    /** The quick-add bar's fields: the rules of the person form's name and the claim form's amount. */
    public record QuickAddForm(@NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }

    /** The person the claim is for, whether they were added by this quick-add, and the new claim. */
    public record QuickAddView(PersonView person, boolean personAdded, ClaimView claim) {
    }

    private final QuickAddService service;
    private final PersonService people;

    public QuickAddController(QuickAddService service, PersonService people) {
        this.service = service;
        this.people = people;
    }

    @PostMapping
    public QuickAddView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody QuickAddForm form,
            @SessionAttribute(SessionController.USER) String user) {
        QuickAddService.Added added = service.add(user, sheetId, form.name(), form.amount());
        return new QuickAddView(PersonView.of(added.person(), people.today(sheetId), people.clashing(sheetId)), added.personAdded(), ClaimView.of(added.claim()));
    }
}
