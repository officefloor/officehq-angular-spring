package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimPlanController {

    /** A plan on the sheet; its amount has two decimals, and generated is how many claims it has made so far. */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, int every, LocalDate end,
            int generated) {
        static PlanView of(ClaimPlan plan, int generated) {
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().setScale(2).toPlainString(),
                    plan.getStart(), plan.getEvery(), plan.getEnd(), generated);
        }
    }

    /**
     * A plan's amount (as the claim form takes one that corrects none: 0.00 or more, at most two
     * decimals), start date, every how many days (a whole number, at least 1) and end date (on or after the start).
     */
    public record PlanRequest(
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            @NotNull LocalDate start,
            @NotNull @Min(1) @Max(36600) Integer every,
            @NotNull LocalDate end) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }
    }

    /** How many claims a generate made. */
    public record GenerateView(int generated) {}

    private final ClaimPlanService service;

    public ClaimPlanController(ClaimPlanService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public List<PlanView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, Integer> made = service.made(sheetId);
        return service.plans(sheetId).stream().map(p -> PlanView.of(p, made.getOrDefault(p.getId(), 0))).toList();
    }

    @PostMapping("/people/{personId}/plans")
    public PlanView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PlanRequest request) {
        return PlanView.of(service.add(sheetId, user, personId, request.amountValue(), request.start(),
                request.every(), request.end()), 0);
    }

    @PostMapping("/plans/generate")
    public GenerateView generate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new GenerateView(service.generate(sheetId, user));
    }
}
