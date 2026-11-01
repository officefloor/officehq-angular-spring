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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class StatementController {

    /** A person's statements as the screen shows them; the last due-total has two decimals, null when n/a. */
    public record StatementSummaryView(Long personId, int count, String lastDueTotal, LocalDate lastDate) {
        static StatementSummaryView of(StatementService.Summary summary) {
            return new StatementSummaryView(summary.personId(), summary.count(),
                    summary.lastDueTotal() == null ? null : summary.lastDueTotal().setScale(2).toPlainString(),
                    summary.lastDate());
        }
    }

    /** A statement just taken: the person's statements and their claims as they now stand. */
    public record StatementTakenView(StatementSummaryView summary, List<ClaimView> claims) {}

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @GetMapping("/statements")
    public List<StatementSummaryView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.summaries(sheetId).stream().map(StatementSummaryView::of).toList();
    }

    @PostMapping("/people/{personId}/statement")
    public StatementTakenView take(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        StatementService.Taken taken = service.take(sheetId, user, personId);
        return new StatementTakenView(StatementSummaryView.of(taken.summary()),
                taken.claims().stream().map(ClaimView::of).toList());
    }

    @PostMapping("/people/{personId}/statement/cancel")
    public StatementTakenView cancel(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        StatementService.Taken taken = service.cancelLast(sheetId, user, personId);
        return new StatementTakenView(StatementSummaryView.of(taken.summary()),
                taken.claims().stream().map(ClaimView::of).toList());
    }
}
