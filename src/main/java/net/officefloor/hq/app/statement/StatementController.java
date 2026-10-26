package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class StatementController {

    /** What the screen shows of a person's statements: how many were taken, and the last one's
     *  due-total (two decimals, or n/a) and its date: the date it was taken as of, else the sheet's today
     *  when it was taken; and a line for each, oldest first by the sheet's today when it was taken (then in
     *  the order taken, those a merge or a transfer brought over among them): its date and due-total. */
    public record StatementView(long personId, int count, String lastDueTotal, LocalDate lastDate,
            List<String> lines) {
        static StatementView of(StatementService.Summary summary) {
            Statement last = summary.last();
            List<String> lines = summary.all().stream()
                    .sorted(Comparator.comparing(Statement::getTakenOn).thenComparing(Statement::getId))
                    .map((s) -> s.getStatedOn() + " " + dueTotal(s)).toList();
            return new StatementView(summary.personId(), summary.count(), dueTotal(last), last.getStatedOn(), lines);
        }

        private static String dueTotal(Statement s) {
            return s.getDueTotal() == null ? "n/a" : s.getDueTotal().toPlainString();
        }
    }

    /** A statement just taken: the person's statements after it, and their claims as it bound them. */
    public record TakenView(StatementView statement, List<ClaimView> claims) {
    }

    /** A statement just cancelled: the person's statements after it (none when none is left), and their
     *  claims, live again. */
    public record CancelledView(StatementView statement, List<ClaimView> claims) {
    }

    /** What a statement is taken as of: a date on or before the sheet's today; none takes it now. */
    public record TakeForm(LocalDate asOf) {
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

    /** Take a person's statement, now or as of a date. */
    @PostMapping("/people/{personId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public TakenView take(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody(required = false) TakeForm form) {
        StatementService.Taken taken = service.take(sheetId.trim(), SignedInUser.name(user), personId,
                form == null ? null : form.asOf());
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
