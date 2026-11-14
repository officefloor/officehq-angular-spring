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
     *  first five plan dates after the sheet's today and on or before its end. */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, Integer every,
            Integer day, LocalDate end, int generated, List<LocalDate> nextDates) {
        static PlanView of(Plan plan, int generated, LocalDate today) {
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().toPlainString(), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), generated, plan.datesAfter(today, NEXT_DATES));
        }
    }

    /** How many next dates a plan shows. */
    private static final int NEXT_DATES = 5;

    /** A plan's fields as typed: the amount, interval and day of the month are checked by the service;
     *  a blank start takes the sheet's today. */
    public record PlanRequest(@NotNull Long personId, @Size(max = 50) String amount, LocalDate start,
            @Size(max = 50) String every, @Size(max = 50) String day, LocalDate end) {
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
                request.every(), request.day(), request.end()), 0, sheets.today(sheet));
    }

    /** Make the plans' claims due by the sheet's today, then record the figures they moved. */
    @PostMapping("/generate")
    public GenerateView generate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        List<Claim> made = rollups.track(sheet, by, () -> service.generate(sheet, by));
        return new GenerateView(made.stream()
                .map(c -> ClaimView.of(c, claims.roundsBy(sheet, c), claims.logOf(c.getId()), List.of()))
                .toList(), views(sheet, null));
    }

    private List<PlanView> views(String sheet, LocalDate asOf) {
        List<Plan> them = service.list(sheet, asOf);
        Map<Long, Integer> generated = service.generated(them, asOf);
        LocalDate today = sheets.today(sheet);
        return them.stream().map(p -> PlanView.of(p, generated.getOrDefault(p.getId(), 0), today)).toList();
    }

    /** A bad amount, interval or day of the month, an end before the start, or a date that is not a real yyyy-mm-dd date. */
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
