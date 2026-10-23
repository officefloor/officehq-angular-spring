package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class PlanController {

    /**
     * What the screen is told about a plan: its amount with two decimals, its dates (every N days, or a day of
     * the month, the other null), how many claims it has made, its next five dates after the sheet's today, and
     * whether it is paused, and the changes of its amount, oldest first.
     */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated, List<LocalDate> nextDates, boolean paused, List<ChangeView> changes) {

        static PlanView of(Plan plan, LocalDate today) {
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().toPlainString(), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getGenerated(), plan.nextDates(today, 5),
                    plan.isPaused(), plan.getChanges().stream().map(ChangeView::of).toList());
        }
    }

    /** A change of a plan's amount: the day it was changed on, and the new amount with two decimals. */
    public record ChangeView(LocalDate on, String amount) {

        static ChangeView of(PlanChange change) {
            return new ChangeView(change.getChangedOn(), change.getAmount().toPlainString());
        }
    }

    /** A plan's new amount as typed, as the claim form takes it. */
    public record AmountForm(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {
    }

    /**
     * A plan as entered on the form: an amount as the claim form takes it, a start, every N days or a day of the
     * month (one of the two, the other absent or blank), and an end.
     */
    public record PlanForm(
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            @NotNull LocalDate start,
            @Pattern(regexp = "|\\d{1,5}") String every,
            @Pattern(regexp = "|\\d{1,2}") String day,
            @NotNull LocalDate end) {
    }

    /** What a generate made: how many claims, the claims in the order made, and the sheet's plans after it. */
    public record GeneratedView(int count, List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /** The sheet's plans in the order they were added. */
    @GetMapping("/plans")
    public List<PlanView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        LocalDate today = service.today(sheetId);
        return service.list(sheetId).stream().map(plan -> PlanView.of(plan, today)).toList();
    }

    /** Add a plan on a person. */
    @PostMapping("/people/{personId}/plans")
    public PlanView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PlanForm form) {
        Plan added = service.add(sheetId, SignedIn.name(user), personId, new BigDecimal(form.amount()),
                form.start(), number(form.every()), number(form.day()), form.end());
        return PlanView.of(added, service.today(sheetId));
    }

    /** Pause a plan from the sheet's today; refused when it is paused already. */
    @PostMapping("/plans/{planId}/pause")
    public PlanView pause(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long planId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return PlanView.of(service.pause(sheetId, SignedIn.name(user), planId, true), service.today(sheetId));
    }

    /** Resume a paused plan on the sheet's today; refused when it is running. */
    @PostMapping("/plans/{planId}/resume")
    public PlanView resume(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long planId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return PlanView.of(service.pause(sheetId, SignedIn.name(user), planId, false), service.today(sheetId));
    }

    /** Change a plan's amount on the sheet's today; refused for a bad amount. */
    @PostMapping("/plans/{planId}/amount")
    public PlanView amount(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long planId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid AmountForm form) {
        return PlanView.of(service.changeAmount(sheetId, SignedIn.name(user), planId, new BigDecimal(form.amount())),
                service.today(sheetId));
    }

    /** A whole number typed on the form, or null when left out. */
    private static Integer number(String typed) {
        return typed == null || typed.isEmpty() ? null : Integer.valueOf(typed);
    }

    /** Make the claims every plan on the sheet is due up to the sheet's today. */
    @PostMapping("/plans/generate")
    public GeneratedView generate(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        PlanService.Generated generated = service.generate(sheetId, SignedIn.name(user));
        LocalDate today = service.today(sheetId);
        return new GeneratedView(generated.claims().size(), generated.claims().stream().map(ClaimView::of).toList(),
                generated.plans().stream().map(plan -> PlanView.of(plan, today)).toList());
    }
}
