package net.officefloor.hq.app.statement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing each person's statements on a sheet, and taking or cancelling a person's statement. */
@RestController
@RequestMapping("/api/statements")
public class StatementController {

    public record ListStatementsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /** {@code asOf}: the date the statement is taken as of; none for now. */
    public record TakeStatementRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long personId, LocalDate asOf) {
    }

    public record CancelStatementRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long personId) {
    }

    /** One of a person's statements still standing: its date and due-total ("n/a" when it had none). */
    public record StatementLine(LocalDate date, String dueTotal) {
    }

    /**
     * A person's statement count, and the last statement's due-total ("n/a" when it had none) and
     * date, both none when no statement is left; and a line for each statement still standing,
     * oldest first by the day it was taken (the sheet's today then; in the order taken on the same day),
     * so one taken as of an earlier date is listed when it was taken.
     */
    public record SummaryResponse(long personId, int count, String dueTotal, LocalDate date,
            List<StatementLine> lines) {
    }

    public record TakenResponse(SummaryResponse summary, List<ClaimResponse> claims) {
    }

    private static SummaryResponse response(StatementService.Summary s) {
        List<StatementLine> lines = s.standing().stream().sorted(Comparator.comparing(Statement::getMadeOn,
                Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(t -> new StatementLine(t.getTakenOn(), StatementService.money(t.getDueTotal()))).toList();
        if (s.last() == null) {
            return new SummaryResponse(s.personId(), s.count(), null, null, lines);
        }
        return new SummaryResponse(s.personId(), s.count(), StatementService.money(s.last().getDueTotal()),
                s.last().getTakenOn(), lines);
    }

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<SummaryResponse> list(@Valid @RequestBody ListStatementsRequest req) {
        return service.list(req.sheet().trim()).stream().map(StatementController::response).toList();
    }

    @PostMapping("/take")
    public TakenResponse take(@Valid @RequestBody TakeStatementRequest req) {
        StatementService.Taken taken = service.take(req.sheet().trim(), req.user().trim(), req.personId(),
                req.asOf());
        return new TakenResponse(response(taken.summary()),
                taken.claims().stream().map(ClaimController::response).toList());
    }

    @PostMapping("/cancel")
    public TakenResponse cancel(@Valid @RequestBody CancelStatementRequest req) {
        StatementService.Cancelled cancelled = service.cancel(req.sheet().trim(), req.user().trim(), req.personId());
        return new TakenResponse(response(cancelled.summary()),
                cancelled.claims().stream().map(ClaimController::response).toList());
    }
}
