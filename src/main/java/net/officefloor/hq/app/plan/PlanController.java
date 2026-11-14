package net.officefloor.hq.app.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.AsOf;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/plans")
public class PlanController {

    /** A plan as the screen shows it, with how many claims it has made so far and its next dates: the
     *  first five plan dates after the sheet's today and on or before its end, those a pause skips left
     *  out, and whether it is paused now. */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, Integer every,
            Integer day, LocalDate end, String sheet, int generated, List<LocalDate> nextDates, boolean paused,
            List<ChangeView> changes) {
        static PlanView of(Plan plan, int generated, List<PlanPause> pauses, List<PlanChange> changes,
                LocalDate today) {
            return new PlanView(plan.getId(), plan.getPersonId(),
                    PlanService.amountAfter(plan, changes).toPlainString(), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getTargetSheetId(), generated,
                    plan.datesAfter(today, NEXT_DATES, d -> PlanService.skipped(pauses, d)),
                    pauses.stream().anyMatch(PlanPause::isOpen),
                    changes.stream().map(c -> new ChangeView(c.getChangedOn(), c.getAmount().toPlainString()))
                            .toList());
        }
    }

    /** A change of a plan's amount: the sheet's today when it was made, and the new amount. */
    public record ChangeView(LocalDate on, String amount) {
    }

    /** A plan's new amount as typed; the service checks it. */
    public record AmountRequest(@Size(max = 50) String amount) {
    }

    /** How many next dates a plan shows. */
    private static final int NEXT_DATES = 5;

    /** A plan's fields as typed: the amount, interval and day of the month are checked by the service;
     *  a blank start takes the sheet's today; a blank sheet makes the claims on this sheet, else it names
     *  the descendant they are made on. */
    public record PlanRequest(@NotNull Long personId, @Size(max = 50) String amount, LocalDate start,
            @Size(max = 50) String every, @Size(max = 50) String day, LocalDate end,
            @Size(max = 200) String sheet) {
    }

    /** The claims a generate made, in the order it made them, and the sheet's plans after it. */
    public record GenerateView(List<ClaimView> made, List<PlanView> plans) {
    }

    private final PlanService service;
    private final ClaimService claims;
    private final RollupService rollups;
    private final SheetService sheets;

    public PlanController(PlanService service, ClaimService claims, RollupService rollups, SheetService sheets) {
        this.service = service;
        this.claims = claims;
        this.rollups = rollups;
        this.sheets = sheets;
    }

    /** The sheet's plans in the order they were added: as they stand now, or as they stood at the end
     *  of the screen's as-of date. */
    @GetMapping
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        return views(sheetId.trim(), AsOf.parse(asOfHeader));
    }

    /** Add a plan for a person on the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PlanRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return PlanView.of(service.add(sheet, by, request.personId(), request.amount(), request.start(),
                request.every(), request.day(), request.end(), request.sheet()), 0, List.of(), List.of(), sheets.today(sheet));
    }

    /** Pause the plan from the sheet's today; refused when it is paused already. */
    @PostMapping("/{planId}/pause")
    public PlanView pause(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long planId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return paused(sheetId, planId, user, true);
    }

    /** Resume the paused plan on the sheet's today; refused when it is running. */
    @PostMapping("/{planId}/resume")
    public PlanView resume(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long planId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return paused(sheetId, planId, user, false);
    }

    /** Change the plan's amount on the sheet's today; refused when the amount is bad. */
    @PostMapping("/{planId}/amount")
    public PlanView amount(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long planId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated AmountRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        Plan plan = rollups.track(sheet, by, () -> service.changeAmount(sheet, by, planId, request.amount()));
        return views(sheet, null).stream().filter(v -> v.id().equals(plan.getId())).findFirst().orElseThrow();
    }

    private PlanView paused(String sheetId, Long planId, String user, boolean on) {
        String sheet = sheetId.trim();
        Plan plan = service.pause(sheet, SignedInUser.decode(user), planId, on);
        return views(sheet, null).stream().filter(v -> v.id().equals(plan.getId())).findFirst().orElseThrow();
    }

    /** Make the plans' claims due by the sheet's today, then record the figures they moved. */
    @PostMapping("/generate")
    public GenerateView generate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        List<Claim> made = rollups.track(sheet, by, () -> service.generate(sheet, by));
        return new GenerateView(made.stream()
                .map(c -> ClaimView.of(c, claims.roundsBy(c.getSheetId(), c), claims.logOf(c.getId()), List.of()))
                .toList(), views(sheet, null));
    }

    private List<PlanView> views(String sheet, LocalDate asOf) {
        List<Plan> them = service.list(sheet, asOf);
        Map<Long, Integer> generated = service.generated(them, asOf);
        Map<Long, List<PlanPause>> pauses = service.pauses(them, asOf);
        Map<Long, List<PlanChange>> changes = service.changes(them, asOf);
        LocalDate today = sheets.today(sheet);
        return them.stream().map(p -> PlanView.of(p, generated.getOrDefault(p.getId(), 0),
                pauses.getOrDefault(p.getId(), List.of()), changes.getOrDefault(p.getId(), List.of()), today))
                .toList();
    }

    /** A bad amount (added or changed), interval or day of the month, an end before the start, a sheet
     *  that is not a descendant, pausing a paused plan or
     *  resuming a running one, or a date that is not a real yyyy-mm-dd date. */
    @ExceptionHandler({PlanRefusedException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void refused() {
    }

    /** A claim the form would refuse after all. */
    @ExceptionHandler({PeriodClosedException.class, PersonRetiredException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict() {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }
}
