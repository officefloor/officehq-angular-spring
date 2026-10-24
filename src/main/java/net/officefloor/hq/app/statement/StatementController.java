package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** A statement; its due-total is sent as text with two decimals so it is never rounded on the way,
     * "n/a" when it had none. */
    public record StatementView(long id, long personId, LocalDate date, String dueTotal, int claims) {
        static StatementView of(Statement statement) {
            return new StatementView(statement.getId(), statement.getPersonId(), statement.getDate(),
                    statement.getDueTotal() == null ? "n/a"
                            : statement.getDueTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    statement.getClaimCount());
        }
    }

    /** A statement taken, with the person's claims as it left them, newest first. */
    public record TakenView(StatementView statement, List<ClaimView> claims) {
    }

    private final StatementService service;
    private final ClaimService claimService;

    private final SheetHistory history;

    public StatementController(StatementService service, ClaimService claimService, SheetHistory history) {
        this.history = history;
        this.service = service;
        this.claimService = claimService;
    }

    @GetMapping("/statements")
    public List<StatementView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<Statement> statements = asOf == null ? service.list(sheetId.trim())
                : history.read(sheetId.trim(), asOf).statements();
        return statements.stream().map(StatementView::of).toList();
    }

    /** A statement to take: as of {@code asOf}, or now when it is null. */
    public record TakeRequest(LocalDate asOf) {
    }

    @PostMapping("/people/{personId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public TakenView take(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody(required = false) TakeRequest request) {
        StatementService.Taken taken = service.take(sheetId.trim(), user.trim(), personId,
                request == null ? null : request.asOf());
        return new TakenView(StatementView.of(taken.statement()), ClaimView.allOf(taken.claims(), claimService));
    }

    /** Cancel the person's last statement; answers the statement cancelled, with the person's claims as
     * it left them, newest first. */
    @DeleteMapping("/people/{personId}/statements/last")
    public TakenView cancelLast(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        StatementService.Taken cancelled = service.cancelLast(sheetId.trim(), user.trim(), personId);
        return new TakenView(StatementView.of(cancelled.statement()), ClaimView.allOf(cancelled.claims(), claimService));
    }
}
