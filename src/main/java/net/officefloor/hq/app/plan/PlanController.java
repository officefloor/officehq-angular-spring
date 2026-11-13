package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The people's plans on a sheet. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class PlanController {

    /** A plan as the screen shows it: the amount with two decimals, dates YYYY-MM-DD, and how many claims it
     *  has made so far. */
    public record PlanView(long id, long personId, String amount, String start, int every, String end,
            int generated) {

        static PlanView of(Plan p) {
            return new PlanView(p.getId(), p.getPersonId(), p.getAmount().toPlainString(), p.getStart().toString(),
                    p.getEvery(), p.getEnd().toString(), p.getGeneratedCount());
        }
    }

    public record PlanRequest(
            @NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotNull LocalDate start,
            @NotNull Integer every,
            @NotNull LocalDate end) {
    }

    /** How many a generate made, the claims it made in the order made, and the sheet's plans. */
    public record GenerateView(int made, List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /** The sheet's plans, in the order they were added. */
    @GetMapping("/plans")
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(PlanView::of).toList();
    }

    /** Add a plan to a person. */
    @PostMapping("/people/{personId}/plans")
    public PlanView add(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PlanRequest request) {
        return PlanView.of(service.add(sheetId.trim(), user.trim(), personId, request.amount(), request.start(),
                request.every(), request.end()));
    }

    /** Make the plans' claims due by the sheet's today. */
    @PostMapping("/plans/generate")
    public GenerateView generate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        PlanService.Generated done = service.generate(sheetId.trim(), user.trim());
        return new GenerateView(done.claims().size(), done.claims().stream().map(ClaimView::of).toList(),
                done.plans().stream().map(PlanView::of).toList());
    }

    /** A plan that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(PlanRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(PlanRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
