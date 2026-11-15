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
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerAllowed;
import net.officefloor.hq.app.sheet.SheetService;
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

    /** A plan as the screen shows it: its amount with two decimals, its dates, its interval in days
     *  or its day of the month (the other null), how many claims it has made so far, the day it was
     *  paused on (null while it runs), its past pauses and its amount changes, oldest first. */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, long generated, LocalDate pausedOn, List<PauseView> pauses, List<ChangeView> changes) {
        static PlanView of(Plan p, Map<Long, Long> made) {
            return new PlanView(p.getId(), p.getPersonId(), p.getAmount().setScale(2).toPlainString(), p.getStart(),
                    p.getEvery(), p.getDay(), p.getEnd(), made.getOrDefault(p.getId(), 0L), p.getPausedOn(),
                    p.getPauses().stream().map(x -> new PauseView(x.from(), x.to())).toList(),
                    p.getChanges().stream().map(c -> new ChangeView(c.on(), c.amount().setScale(2).toPlainString())).toList());
        }
    }

    /** A past pause: from the day the plan was paused (inclusive) to the day it was resumed (exclusive). */
    public record PauseView(LocalDate from, LocalDate to) {
    }

    /** An amount change: the sheet's today it was made on and the new amount with two decimals. */
    public record ChangeView(LocalDate on, String amount) {
    }

    /** A plan's new amount: one the claim form takes for a new claim (0.00 or more, up to two decimals). */
    public record AmountForm(@NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount) {
    }

    /** A plan as the form submits it: an amount the claim form takes for a new claim (0.00 or
     *  more, up to two decimals), a start (null: the sheet's today), either an interval of whole
     *  days (at least 1) or a day of the month (1 to 31), and an end. */
    public record PlanForm(
            @NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount,
            LocalDate start,
            @Min(1) Integer every,
            @Min(1) @Max(31) Integer day,
            @NotNull LocalDate end) {
    }

    /** A generate's result: the claims made, in the order they were made, and the sheet's plans now. */
    public record GenerateView(List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService service;
    private final ClaimController claims;
    private final SettingsService settings;
    private final SheetService sheets;

    public PlanController(PlanService service, ClaimController claims, SettingsService settings, SheetService sheets) {
        this.service = service;
        this.claims = claims;
        this.settings = settings;
        this.sheets = sheets;
    }

    @GetMapping("/plans")
    public List<PlanView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        Map<Long, Long> made = service.madeCounts(sheetId);
        return service.list(sheetId).stream().map(p -> PlanView.of(p, made)).toList();
    }

    @PostMapping("/people/{personId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PlanForm body) {
        Plan plan = service.add(sheetId, user.trim(), personId, new BigDecimal(body.amount()), body.start(), body.every(),
                body.day(), body.end());
        return PlanView.of(plan, Map.of());
    }

    @PostMapping("/plans/{planId}/pause")
    public PlanView pause(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long planId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PlanView.of(service.setPaused(sheetId, user.trim(), planId, true), service.madeCounts(sheetId));
    }

    @PostMapping("/plans/{planId}/resume")
    public PlanView resume(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long planId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PlanView.of(service.setPaused(sheetId, user.trim(), planId, false), service.madeCounts(sheetId));
    }

    @PostMapping("/plans/{planId}/amount")
    public PlanView changeAmount(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long planId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody AmountForm body) {
        return PlanView.of(service.changeAmount(sheetId, user.trim(), planId, new BigDecimal(body.amount())),
                service.madeCounts(sheetId));
    }

    /** Generate the plans' claims; resolves to the claims made and the sheet's plans with their
     *  counts. Each claim is refused for a viewer and on an archived sheet, so it makes none. */
    @PostMapping("/plans/generate")
    @ViewerAllowed
    public GenerateView generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        if (settings.isViewer(sheetId, user.trim()) || sheets.archived(sheetId)) {
            return new GenerateView(List.of(), list(sheetId, user));
        }
        List<ClaimView> made = service.generate(sheetId, user.trim()).stream().map(claims::view).toList();
        return new GenerateView(made, list(sheetId, user));
    }
}
