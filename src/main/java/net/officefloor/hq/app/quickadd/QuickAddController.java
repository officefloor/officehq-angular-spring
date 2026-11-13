package net.officefloor.hq.app.quickadd;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.NameInUseException;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}/quick-add")
public class QuickAddController {

    /** A quick-add: the person's name (not blank) and the claim's amount, 0.00 or more with up to
     *  two decimals. */
    public record QuickAddRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /** The person the claim is for, as stored now, and the claim added. */
    public record QuickAddView(PersonView person, ClaimView claim) {
    }

    private final QuickAddService service;
    private final StatementService statements;

    public QuickAddController(QuickAddService service, StatementService statements) {
        this.service = service;
        this.statements = statements;
    }

    /** Add a DRAFT claim for the named person, adding them first when the name is not on the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated QuickAddRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        QuickAddService.Added added = service.add(sheet, by, request.name(), request.amount());
        return new QuickAddView(PersonView.of(added.person(), statements.summary(sheet, added.person().getId())),
                ClaimView.of(added.claim()));
    }

    /** A claim dated in a closed period or after its person retired, or a name taken at the same moment, is refused. */
    @ExceptionHandler({PeriodClosedException.class, PersonRetiredException.class, NameInUseException.class,
            DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** An amount that is not a number is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
