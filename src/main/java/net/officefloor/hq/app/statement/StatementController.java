package net.officefloor.hq.app.statement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing each person's statements on a sheet, and taking a person's statement. */
@RestController
@RequestMapping("/api/statements")
public class StatementController {

    public record ListStatementsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record TakeStatementRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long personId) {
    }

    /** A person's statement count, and the last statement's due-total ("n/a" when none) and date. */
    public record SummaryResponse(long personId, int count, String dueTotal, LocalDate date) {
    }

    public record TakenResponse(SummaryResponse summary, List<ClaimResponse> claims) {
    }

    private static SummaryResponse response(StatementService.Summary s) {
        return new SummaryResponse(s.personId(), s.count(), StatementService.money(s.last().getDueTotal()),
                s.last().getTakenOn());
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
        StatementService.Taken taken = service.take(req.sheet().trim(), req.user().trim(), req.personId());
        return new TakenResponse(response(taken.summary()),
                taken.claims().stream().map(ClaimController::response).toList());
    }
}
