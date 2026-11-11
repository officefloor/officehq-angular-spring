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
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing, adding, pausing, resuming and changing the amount of the people's plans, and generating the claims they make. */
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

    public record PlanActionRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long planId) {
    }

    /** A plan's new amount: one the claim form takes without a correction. */
    public record ChangeAmountRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long planId, @NotNull @Pattern(regexp = ClaimController.AMOUNT) String amount) {
    }

    /** A plan, its amount's changes oldest first, each as {@code <date> amount=<amount>}. */
    public record PlanResponse(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated, String nextDates, boolean paused, List<String> changes) {
    }

    /** What a generate did: how many claims were made, those claims in order, and the plans after it. */
    public record GenerateResponse(int made, List<ClaimResponse> claims, List<PlanResponse> plans) {
    }

    /** How many of a plan's next dates it shows. */
    private static final int NEXT_DATES = 5;

    private static PlanResponse response(Plan plan, LocalDate today) {
        return new PlanResponse(plan.getId(), plan.getPersonId(),
                plan.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), plan.getStart(),
                plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getGenerated(),
                String.join(", ", plan.datesAfter(today, NEXT_DATES).stream().map(LocalDate::toString).toList()),
                plan.isPaused(),
                plan.getChanges().stream().map(c -> c.getChangedOn() + " amount="
                        + c.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()).toList());
    }

    private static Integer number(String digits) {
        return digits == null || digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    private final PlanService service;
    private final SheetService sheets;

    public PlanController(PlanService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    @PostMapping("/list")
    public List<PlanResponse> list(@Valid @RequestBody ListPlansRequest req) {
        String sheet = req.sheet().trim();
        LocalDate today = sheets.today(sheet);
        return service.list(sheet).stream().map(plan -> response(plan, today)).toList();
    }

    @PostMapping("/add")
    public PlanResponse add(@Valid @RequestBody AddPlanRequest req) {
        String sheet = req.sheet().trim();
        Plan plan = service.add(sheet, req.user().trim(), req.personId(), ClaimController.amount(req.amount()),
                req.start(), number(req.every()), number(req.day()), req.end());
        return response(plan, sheets.today(sheet));
    }

    @PostMapping("/pause")
    public PlanResponse pause(@Valid @RequestBody PlanActionRequest req) {
        String sheet = req.sheet().trim();
        Plan plan = service.pause(sheet, req.user().trim(), req.planId());
        return response(plan, sheets.today(sheet));
    }

    @PostMapping("/resume")
    public PlanResponse resume(@Valid @RequestBody PlanActionRequest req) {
        String sheet = req.sheet().trim();
        Plan plan = service.resume(sheet, req.user().trim(), req.planId());
        return response(plan, sheets.today(sheet));
    }

    @PostMapping("/amount")
    public PlanResponse changeAmount(@Valid @RequestBody ChangeAmountRequest req) {
        String sheet = req.sheet().trim();
        Plan plan = service.changeAmount(sheet, req.user().trim(), req.planId(), ClaimController.amount(req.amount()));
        return response(plan, sheets.today(sheet));
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest req) {
        String sheet = req.sheet().trim();
        List<ClaimResponse> made = service.generate(sheet, req.user().trim()).stream()
                .map(ClaimController::response).toList();
        LocalDate today = sheets.today(sheet);
        return new GenerateResponse(made.size(), made,
                service.list(sheet).stream().map(plan -> response(plan, today)).toList());
    }
}
