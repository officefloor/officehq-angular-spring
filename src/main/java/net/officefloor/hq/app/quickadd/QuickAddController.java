package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.statement.StatementService;
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

    /** What a quick-add made: the person the claim is for (and whether they were added) and the claim. */
    public record QuickAddView(PersonView person, boolean personAdded, ClaimView claim) {
    }

    /** The quick-add bar: a required name, and a required amount of 0.00 or more with up to two
     *  decimals. */
    public record QuickAddForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    private final QuickAddService service;
    private final StatementService statements;

    public QuickAddController(QuickAddService service, StatementService statements) {
        this.service = service;
        this.statements = statements;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody QuickAddForm form) {
        String sheet = sheetId.trim();
        QuickAddService.Added added = service.add(sheet, user.trim(), form.name().trim(), form.amountValue());
        return new QuickAddView(PersonView.of(added.person(), statements.summary(sheet, added.person().getId())),
                added.personAdded(), ClaimView.of(added.claim()));
    }
}
