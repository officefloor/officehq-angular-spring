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
import net.officefloor.hq.app.setting.OpenToViewer;
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

    /**
     * A plan on the sheet; its amount has two decimals, every or day is set (the other null), and generated is how
     * many claims it has made so far; nextDates are its first five dates after the sheet's today, on or before its
     * end and not skipped by a pause, comma and space separated; paused is whether it is paused now; changes its
     * amount changes, oldest first; sheet is the descendant sheet it generates on, null when its own.
     */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, String sheet, int generated, String nextDates, boolean paused, List<ChangeView> changes) {
        static PlanView of(ClaimPlan plan, int generated, LocalDate today, List<ClaimPlanPause> pauses,
                List<ClaimPlanChange> changes) {
            String next = String.join(", ", plan.datesAfter(today, 5, ClaimPlanService.skippedBy(pauses)).stream()
                    .map(LocalDate::toString).toList());
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().setScale(2).toPlainString(),
                    plan.getStart(), plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getTargetSheet(), generated, next,
                    pauses.stream().anyMatch(ClaimPlanPause::isOpen),
                    changes.stream().map(ChangeView::of).toList());
        }
    }

    /** A change of a plan's amount: the day it was changed and the new amount, with two decimals. */
    public record ChangeView(LocalDate on, String amount) {
        static ChangeView of(ClaimPlanChange change) {
            return new ChangeView(change.getChangedOn(), change.getAmount().setScale(2).toPlainString());
        }
    }

    /** A plan's new amount, as the plan form takes one. */
    public record AmountRequest(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }
    }

    /**
     * A plan's amount (as the claim form takes one that corrects none: 0.00 or more, at most two
     * decimals), start date, either every how many days (a whole number, at least 1) or the day of the month it runs
     * on monthly (1 to 31), end date (on or after the start), and the descendant sheet it generates on (blank or
     * null: its own).
     */
    public record PlanRequest(
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            @NotNull LocalDate start,
            @Min(1) @Max(36600) Integer every,
            @Min(1) @Max(31) Integer day,
            @NotNull LocalDate end,
            @Size(max = 100) @Pattern(regexp = "\\s*([A-Za-z0-9._-]+)?\\s*") String sheet) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }

        String sheetValue() {
            return sheet == null || sheet.isBlank() ? null : sheet.strip();
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
        Map<Long, List<ClaimPlanPause>> pauses = service.pauses(sheetId);
        Map<Long, List<ClaimPlanChange>> changes = service.changes(sheetId);
        LocalDate today = service.today(sheetId);
        return service.plans(sheetId).stream()
                .map(p -> PlanView.of(p, made.getOrDefault(p.getId(), 0), today,
                        pauses.getOrDefault(p.getId(), List.of()), changes.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @PostMapping("/people/{personId}/plans")
    public PlanView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PlanRequest request) {
        ClaimPlan plan = service.add(sheetId, user, personId, request.amountValue(), request.start(),
                request.every(), request.day(), request.end(), request.sheetValue());
        return PlanView.of(plan, 0, service.today(sheetId), List.of(), List.of());
    }

    /** Pause the plan from the sheet's today; refused when it is already paused. */
    @PostMapping("/plans/{planId}/pause")
    public PlanView pause(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId, service.pause(sheetId, user, planId));
    }

    /** Resume the plan from the sheet's today; refused when it is not paused. */
    @PostMapping("/plans/{planId}/resume")
    public PlanView resume(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId, service.resume(sheetId, user, planId));
    }

    /** Change the plan's amount on the sheet's today; the claims generated from then on take it. */
    @PostMapping("/plans/{planId}/amount")
    public PlanView changeAmount(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AmountRequest request) {
        return view(sheetId, service.changeAmount(sheetId, user, planId, request.amountValue()));
    }

    private PlanView view(String sheetId, ClaimPlan plan) {
        return PlanView.of(plan, service.made(sheetId).getOrDefault(plan.getId(), 0), service.today(sheetId),
                service.pauses(sheetId).getOrDefault(plan.getId(), List.of()),
                service.changes(sheetId).getOrDefault(plan.getId(), List.of()));
    }

    @OpenToViewer
    @PostMapping("/plans/generate")
    public GenerateView generate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new GenerateView(service.generate(sheetId, user));
    }
}
