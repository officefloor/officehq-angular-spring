package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

    /** The quick-add bar's fields: the person's name and the claim's amount. */
    public record QuickAddForm(@NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /** The person the claim was added to (perhaps just added), and the claim. */
    public record QuickAddView(PersonView person, ClaimView claim) {
    }

    private final QuickAddService service;
    private final SheetService sheets;
    private final PersonService people;

    public QuickAddController(QuickAddService service, SheetService sheets, PersonService people) {
        this.service = service;
        this.sheets = sheets;
        this.people = people;
    }

    /** Add a draft claim to the named person, adding the person first when the name is new. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid QuickAddForm form) {
        String name = SignedInUser.name(user);
        QuickAddService.Added added = service.add(sheetId.trim(), name, form.name(), form.amount());
        return new QuickAddView(PersonView.of(added.person(), sheets.today(added.person().getSheetId()),
                people.isWatched(added.person().getId(), name), people.isClashing(added.person())),
                ClaimView.of(added.claim()));
    }
}
