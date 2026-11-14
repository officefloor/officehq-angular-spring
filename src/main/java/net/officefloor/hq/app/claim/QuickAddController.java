package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.session.CurrentUser;
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

    /** The quick-add bar's values: a person's name (as the person form takes it) and a claim's
     *  amount (as the claim form takes it). */
    public record QuickAddForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount) {
    }

    /** The person as they now show (added when personAdded) and the claim just added. */
    public record QuickAddView(PersonView person, boolean personAdded, ClaimView claim) {
    }

    private final QuickAddService service;
    private final PersonService people;
    private final ClaimService claims;

    public QuickAddController(QuickAddService service, PersonService people, ClaimService claims) {
        this.service = service;
        this.people = people;
        this.claims = claims;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody QuickAddForm body) {
        QuickAddService.QuickAdd q = service.add(sheetId, user.trim(), body.name(), new BigDecimal(body.amount()));
        return new QuickAddView(PersonView.of(q.person(), people.today(sheetId), people.statementSummary(q.person().getId()),
                people.patronIds(q.person().getId())),
                q.personAdded(), ClaimView.of(q.claim(), claims.logOf(q.claim().getId())));
    }
}
