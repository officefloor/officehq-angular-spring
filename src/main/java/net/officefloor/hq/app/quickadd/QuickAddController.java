package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The quick-add bar: a claim for a person named, who is added first when not on the sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/quick-add")
public class QuickAddController {

    /** The name and amount follow the person and claim forms' rules. */
    public record QuickAddForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount) {}

    public record QuickAddView(PersonView person, boolean personCreated, ClaimView claim) {}

    private final QuickAddService service;
    private final StatementService statements;
    private final PersonService people;
    private final SheetService sheets;

    public QuickAddController(QuickAddService service, StatementService statements, PersonService people,
            SheetService sheets) {
        this.sheets = sheets;
        this.service = service;
        this.statements = statements;
        this.people = people;
    }

    @PostMapping
    public QuickAddView add(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody QuickAddForm form) {
        QuickAddService.Added added = service.add(user, sheetId, form.name(), new BigDecimal(form.amount()));
        return new QuickAddView(PersonView.of(added.person(), sheets.today(sheetId), statements.summaries(sheetId),
                people.patronIds(sheetId)), added.personCreated(),
                ClaimView.of(added.claim()));
    }
}
