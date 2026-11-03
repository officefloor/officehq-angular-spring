package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.role.ViewerAllowed;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class PlanController {

    /** What the screen shows of a plan: its amount as text with two decimals, its dates, its interval
     *  or day of the month (the other null), how many claims it has made so far, and its next dates:
     *  the first five after the sheet's today and on or before its end; whether it is paused; and the
     *  changes of its amount, oldest first; and the descendant sheet its claims are made on (null: its own). */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated, List<LocalDate> nextDates, boolean paused, List<ChangeView> changes,
            String sheet) {
        static PlanView of(Plan plan, LocalDate today) {
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().setScale(2).toPlainString(),
                    plan.getStartDate(), plan.getEveryDays(), plan.getMonthDay(), plan.getEndDate(),
                    plan.getGenerated(), plan.nextAfter(today, 5), plan.isPaused(),
                    plan.getChanges().stream().map(ChangeView::of).toList(), plan.getTargetSheetId());
        }
    }

    /** A change of a plan's amount: the day it was changed on and the new amount as text with two decimals. */
    public record ChangeView(LocalDate on, String amount) {
        static ChangeView of(PlanChange change) {
            return new ChangeView(change.getChangedOn(), change.getAmount().setScale(2).toPlainString());
        }
    }

    /** The plan's new amount: required, with up to two decimals. */
    public record AmountForm(@NotNull @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }
    }

    /** The plan form: a required amount with up to two decimals, an optional start (the sheet's today
     *  when left out), either a whole number of days it repeats every or a day of the month it falls
     *  on (the other blank), a required end, and an optional descendant sheet its claims are made on. */
    public record PlanForm(@NotNull @Pattern(regexp = "\\s*\\d{1,12}(\\.\\d{1,2})?\\s*") String amount,
            LocalDate start, @Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String every,
            @Pattern(regexp = "\\s*(\\d{1,2})?\\s*") String day, @NotNull LocalDate end,
            @Size(max = 100) String sheet) {

        String sheetValue() {
            return sheet == null || sheet.isBlank() ? null : sheet.trim();
        }

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }

        Integer everyValue() {
            return every == null || every.isBlank() ? null : Integer.valueOf(every.trim());
        }

        Integer dayValue() {
            return day == null || day.isBlank() ? null : Integer.valueOf(day.trim());
        }
    }

    /** How many claims a generate made. */
    public record Generated(int made) {
    }

    private final PlanService service;
    private final SheetService sheets;

    public PlanController(PlanService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    @GetMapping("/plans")
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        LocalDate today = sheets.today(sheet);
        return service.list(sheet).stream().map(plan -> PlanView.of(plan, today)).toList();
    }

    @PostMapping("/people/{personId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PlanForm form) {
        String sheet = sheetId.trim();
        return PlanView.of(service.add(sheet, user.trim(), personId, form.amountValue(), form.start(),
                form.everyValue(), form.dayValue(), form.end(), form.sheetValue()), sheets.today(sheet));
    }

    @PostMapping("/plans/{planId}/pause")
    public PlanView pause(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        String sheet = sheetId.trim();
        return PlanView.of(service.setPaused(sheet, user.trim(), planId, true), sheets.today(sheet));
    }

    @PostMapping("/plans/{planId}/resume")
    public PlanView resume(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        String sheet = sheetId.trim();
        return PlanView.of(service.setPaused(sheet, user.trim(), planId, false), sheets.today(sheet));
    }

    @PostMapping("/plans/{planId}/amount")
    public PlanView changeAmount(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long planId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AmountForm form) {
        String sheet = sheetId.trim();
        return PlanView.of(service.changeAmount(sheet, user.trim(), planId, form.amountValue()), sheets.today(sheet));
    }

    @ViewerAllowed
    @PostMapping("/plans/generate")
    public Generated generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new Generated(service.generate(sheetId.trim(), user.trim()));
    }
}
