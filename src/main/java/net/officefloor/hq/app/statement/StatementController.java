package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/people/{personId}/statements")
@Validated
public class StatementController {

    /** What the screen is told after a statement: the person and their claims, newest first. */
    public record StatementView(PersonView person, List<ClaimView> claims) {
    }

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    /** Take a statement for a person. */
    @PostMapping
    public StatementView take(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        StatementService.Stated stated = service.take(sheetId, SignedIn.name(user), personId);
        return new StatementView(PersonView.of(stated.person()),
                stated.claims().stream().map(ClaimView::of).toList());
    }

    /** Cancel the person's last statement. */
    @DeleteMapping("/last")
    public StatementView cancel(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        StatementService.Stated stated = service.cancel(sheetId, SignedIn.name(user), personId);
        return new StatementView(PersonView.of(stated.person()),
                stated.claims().stream().map(ClaimView::of).toList());
    }
}
