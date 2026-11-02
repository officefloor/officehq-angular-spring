package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/people/{personId}/statements")
@Validated
public class StatementController {

    /** A statement just taken: the person as they now show, and their claims as it bound them. */
    public record TakenView(PersonView person, List<ClaimView> claims) {
    }

    /** The statement form: the day the statement is taken as of; left out, the sheet's today. */
    public record StatementForm(LocalDate asOf) {
    }

    private final StatementService service;
    private final SheetService sheets;

    public StatementController(StatementService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TakenView take(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) StatementForm form) {
        StatementService.Taken taken = service.take(sheetId.trim(), user.trim(), personId,
                form == null ? null : form.asOf());
        return new TakenView(PersonView.of(taken.person(), taken.summary(), sheets.today(sheetId.trim())),
                taken.claims().stream().map(ClaimView::of).toList());
    }

    /** Cancel the person's last statement. */
    @DeleteMapping("/last")
    public TakenView cancelLast(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        StatementService.Taken cancelled = service.cancelLast(sheetId.trim(), user.trim(), personId);
        return new TakenView(PersonView.of(cancelled.person(), cancelled.summary(), sheets.today(sheetId.trim())),
                cancelled.claims().stream().map(ClaimView::of).toList());
    }
}
