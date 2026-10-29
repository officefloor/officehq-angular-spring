package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class StatementController {

    /** One statement standing, as its line shows it: its date and due-total (null for n/a). */
    public record LineView(LocalDate date, String dueTotal) {
        static LineView of(Statement s) {
            return new LineView(s.getDate(), s.getDueTotal() == null ? null : s.getDueTotal().toPlainString());
        }
    }

    /**
     * A person's statements as the screen shows them: how many, the last one's due-total (null for n/a) and date,
     * and a line for each, oldest first (by date, then in their order).
     */
    public record StatementView(Long personId, int count, String dueTotal, LocalDate date, List<LineView> lines) {
        static StatementView of(StatementService.Summary s) {
            List<LineView> lines = s.statements().stream().map(LineView::of)
                    .sorted(Comparator.comparing(LineView::date)).toList();
            if (s.last() == null) {
                return new StatementView(s.personId(), 0, null, null, lines);
            }
            return new StatementView(s.personId(), s.count(),
                    s.last().getDueTotal() == null ? null : s.last().getDueTotal().toPlainString(),
                    s.last().getDate(), lines);
        }
    }

    /** What a statement is taken with: the date it is taken as of (null or missing: now). */
    public record TakeRequest(LocalDate asOf) {
    }

    /** A statement just taken, with all the person's claims as it left them. */
    public record TakenView(StatementView statement, List<ClaimView> claims) {
    }

    private final StatementService service;
    private final ClaimService claims;

    public StatementController(StatementService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    /** Each person's statements on the sheet, for the people who have any. */
    @GetMapping("/statements")
    public List<StatementView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(StatementView::of).toList();
    }

    @PostMapping("/people/{personId}/statement")
    public TakenView take(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @RequestBody(required = false) TakeRequest request,
            @SessionAttribute(SessionController.USER) String user) {
        LocalDate asOf = request == null ? null : request.asOf();
        StatementView taken = StatementView.of(service.take(user, sheetId, personId, asOf));
        List<ClaimView> theirs = claims.list(sheetId).stream().filter(c -> c.getPersonId().equals(personId))
                .map(ClaimView::of).toList();
        return new TakenView(taken, theirs);
    }

    /** Cancels the person's last statement; answers with their statements and claims as it left them. */
    @DeleteMapping("/people/{personId}/statement")
    public TakenView cancel(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @SessionAttribute(SessionController.USER) String user) {
        StatementView left = StatementView.of(service.cancel(user, sheetId, personId));
        List<ClaimView> theirs = claims.list(sheetId).stream().filter(c -> c.getPersonId().equals(personId))
                .map(ClaimView::of).toList();
        return new TakenView(left, theirs);
    }
}
