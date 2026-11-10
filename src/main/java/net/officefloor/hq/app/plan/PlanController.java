package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and adding the people's plans, and generating the claims they make. */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    public record ListPlansRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /**
     * A plan's amount is one the claim form takes without a correction; every is a whole number of
     * days, or day a day of the month; exactly one of them is given.
     */
    public record AddPlanRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long personId, @NotNull @Pattern(regexp = ClaimController.AMOUNT) String amount,
            @NotNull LocalDate start, @Pattern(regexp = "\\d{1,6}") String every,
            @Pattern(regexp = "\\d{1,6}") String day, @NotNull LocalDate end) {
    }

    public record GenerateRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user) {
    }

    public record PlanResponse(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated) {
    }

    /** What a generate did: how many claims were made, those claims in order, and the plans after it. */
    public record GenerateResponse(int made, List<ClaimResponse> claims, List<PlanResponse> plans) {
    }

    private static PlanResponse response(Plan plan) {
        return new PlanResponse(plan.getId(), plan.getPersonId(),
                plan.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), plan.getStart(),
                plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getGenerated());
    }

    private static Integer number(String digits) {
        return digits == null || digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<PlanResponse> list(@Valid @RequestBody ListPlansRequest req) {
        return service.list(req.sheet().trim()).stream().map(PlanController::response).toList();
    }

    @PostMapping("/add")
    public PlanResponse add(@Valid @RequestBody AddPlanRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.personId(),
                ClaimController.amount(req.amount()), req.start(), number(req.every()), number(req.day()),
                req.end()));
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest req) {
        String sheet = req.sheet().trim();
        List<ClaimResponse> made = service.generate(sheet, req.user().trim()).stream()
                .map(ClaimController::response).toList();
        return new GenerateResponse(made.size(), made,
                service.list(sheet).stream().map(PlanController::response).toList());
    }
}
