package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The plans of a sheet's people. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class PlanController {

    /**
     * A plan as shown: its amount with two decimals, its dates as yyyy-mm-dd, its interval in days or its day of the
     * month (the other null), how many claims it has made, and its first five dates after the sheet's today up to its
     * end, comma and space separated, those paused left out; whether it is paused now; and the changes of its amount,
     * oldest first.
     */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, String sheet, long generated, String nextDates, boolean paused, List<ChangeView> changes) {

        static PlanView of(Plan plan, long generated, List<LocalDate> nextDates, boolean paused,
                List<PlanChange> changes) {
            return new PlanView(plan.getId(), plan.getPersonId(), money(plan.getAmount()), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getTargetSheetId(), generated,
                    nextDates.stream().map(LocalDate::toString).collect(Collectors.joining(", ")), paused,
                    changes.stream().map(c -> new ChangeView(c.getChangedOn(), money(c.getAmount()))).toList());
        }

        private static String money(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    /** A change of a plan's amount: the day it was made and the new amount with two decimals. */
    public record ChangeView(LocalDate date, String amount) {
    }

    /** A plan's new amount, with up to two decimals. */
    public record AmountRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * An amount with up to two decimals, a start and end date, and either the interval in whole days or the day of the
     * month (the other blank or left out), and optionally the id of a descendant sheet its claims are made on (blank
     * or left out: this sheet).
     */
    public record PlanRequest(
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            @NotNull LocalDate start,
            @Pattern(regexp = "\\s*(\\d{1,6})?\\s*") String every,
            @Pattern(regexp = "\\s*(\\d{1,6})?\\s*") String day,
            @Size(max = 100) String sheet,
            @NotNull LocalDate end) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }

        Integer everyValue() {
            return whole(every);
        }

        Integer dayValue() {
            return whole(day);
        }

        String sheetValue() {
            return sheet == null || sheet.isBlank() ? null : sheet.trim();
        }

        private static Integer whole(String text) {
            return text == null || text.isBlank() ? null : Integer.valueOf(text.trim());
        }
    }

    /** How many claims a generate made. */
    public record GenerateResult(int made) {
    }

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /** The sheet's plans in the order they were added. */
    @GetMapping("/plans")
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, Long> generated = service.generatedCounts(sheetId.trim());
        Map<Long, List<LocalDate>> next = service.nextDates(sheetId.trim());
        Set<Long> paused = service.paused(sheetId.trim());
        Map<Long, List<PlanChange>> changes = service.changes(sheetId.trim());
        return service.list(sheetId.trim()).stream()
                .map(plan -> PlanView.of(plan, generated.getOrDefault(plan.getId(), 0L),
                        next.getOrDefault(plan.getId(), List.of()), paused.contains(plan.getId()),
                        changes.getOrDefault(plan.getId(), List.of())))
                .toList();
    }

    @PostMapping("/people/{personId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PlanRequest request) {
        Plan plan = service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.start(),
                request.everyValue(), request.dayValue(), request.end(), request.sheetValue());
        return PlanView.of(plan, 0, service.nextDates(sheetId.trim(), plan), false, List.of());
    }

    /** Pause the plan from the sheet's today; refused when it is already paused. */
    @PostMapping("/plans/{planId}/pause")
    public PlanView pause(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return pauseOrResume(sheetId.trim(), user.trim(), planId, true);
    }

    /** Resume the plan on the sheet's today; refused when it is not paused. */
    @PostMapping("/plans/{planId}/resume")
    public PlanView resume(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return pauseOrResume(sheetId.trim(), user.trim(), planId, false);
    }

    private PlanView pauseOrResume(String sheetId, String user, Long planId, boolean on) {
        Plan plan = service.pause(sheetId, user, planId, on);
        return PlanView.of(plan, service.generatedCounts(sheetId).getOrDefault(planId, 0L),
                service.nextDates(sheetId, plan), on, service.changes(sheetId, plan));
    }

    /** Change the plan's amount for the claims generated from now on; refused when it is not more than 0.00. */
    @PostMapping("/plans/{planId}/amount")
    public PlanView changeAmount(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AmountRequest request) {
        Plan plan = service.changeAmount(sheetId.trim(), user.trim(), planId, request.amountValue());
        return PlanView.of(plan, service.generatedCounts(sheetId.trim()).getOrDefault(planId, 0L),
                service.nextDates(sheetId.trim(), plan), service.paused(sheetId.trim()).contains(planId),
                service.changes(sheetId.trim(), plan));
    }

    /** Make the claims of every plan date due and not yet made. */
    @PostMapping("/plans/generate")
    public GenerateResult generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new GenerateResult(service.generate(sheetId.trim(), user.trim()));
    }
}
