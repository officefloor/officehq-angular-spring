package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class StatementController {

    /** What the screen shows of a person's statements: how many were taken, and the last one's
     *  due-total (two decimals, or n/a) and the sheet's today when it was taken. */
    public record StatementView(long personId, int count, String lastDueTotal, LocalDate lastDate) {
        static StatementView of(StatementService.Summary summary) {
            Statement last = summary.last();
            return new StatementView(summary.personId(), summary.count(),
                    last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString(), last.getTakenOn());
        }
    }

    /** A statement just taken: the person's statements after it, and their claims as it bound them. */
    public record TakenView(StatementView statement, List<ClaimView> claims) {
    }

    /** A statement just cancelled: the person's statements after it (none when none is left), and their
     *  claims, live again. */
    public record CancelledView(StatementView statement, List<ClaimView> claims) {
    }

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    /** The statements of each person on the sheet who has had one taken. */
    @GetMapping("/statements")
    public List<StatementView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(StatementView::of).toList();
    }

    /** Take a person's statement. */
    @PostMapping("/people/{personId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public TakenView take(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        StatementService.Taken taken = service.take(sheetId.trim(), SignedInUser.name(user), personId);
        return new TakenView(StatementView.of(taken.summary()),
                taken.claims().stream().map(ClaimView::of).toList());
    }

    /** Cancel a person's last statement; a steward only. */
    @DeleteMapping("/people/{personId}/statements/last")
    public CancelledView cancel(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        StatementService.Cancelled cancelled = service.cancel(sheetId.trim(), SignedInUser.name(user), personId);
        return new CancelledView(cancelled.summary() == null ? null : StatementView.of(cancelled.summary()),
                cancelled.claims().stream().map(ClaimView::of).toList());
    }
}
