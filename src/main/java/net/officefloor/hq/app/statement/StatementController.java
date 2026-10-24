package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The statements taken of the people on a sheet. The signed-in user's name travels in the
 * {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class StatementController {

    /** A statement; its due-total is sent as text with two decimals so it is never rounded on the way. */
    public record StatementView(long id, long personId, LocalDate date, String dueTotal, int claims) {
        static StatementView of(Statement statement) {
            return new StatementView(statement.getId(), statement.getPersonId(), statement.getDate(),
                    statement.getDueTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    statement.getClaimCount());
        }
    }

    /** A statement taken, with the person's claims as it left them, newest first. */
    public record TakenView(StatementView statement, List<ClaimView> claims) {
    }

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @GetMapping("/statements")
    public List<StatementView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(StatementView::of).toList();
    }

    @PostMapping("/people/{personId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public TakenView take(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        StatementService.Taken taken = service.take(sheetId.trim(), user.trim(), personId);
        return new TakenView(StatementView.of(taken.statement()), taken.claims().stream().map(ClaimView::of).toList());
    }
}
