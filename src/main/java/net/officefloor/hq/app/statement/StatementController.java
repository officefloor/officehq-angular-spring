package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Statements taken of people on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class StatementController {

    /** A person's statements: how many, and the last one's due-total ("n/a" when none could be given) and date. */
    public record SummaryView(long personId, int count, String dueTotal, LocalDate date) {

        static SummaryView of(StatementService.Summary summary) {
            return new SummaryView(summary.personId(), summary.count(), summary.dueTotal(), summary.takenOn());
        }
    }

    /** A statement just taken: the person's summary and their claims as it left them, newest first. */
    public record TakenView(SummaryView summary, List<ClaimView> claims) {
    }

    private final StatementService statements;

    public StatementController(StatementService statements) {
        this.statements = statements;
    }

    @GetMapping("/statements")
    public List<SummaryView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return statements.list(sheetId).stream().map(SummaryView::of).toList();
    }

    @PostMapping("/people/{personId}/statements")
    public TakenView take(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        StatementService.Taken taken = statements.take(sheetId, user, personId);
        return new TakenView(SummaryView.of(taken.summary()), taken.claims().stream().map(ClaimView::of).toList());
    }
}
