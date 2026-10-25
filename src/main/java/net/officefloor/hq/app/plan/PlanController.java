package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
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
@RequestMapping("/api/sheets/{sheetId}/plans")
@Validated
public class PlanController {

    /** What the screen shows of a plan; the amount always has two decimals. Generated is how many claims
     *  it has made so far; next dates are its first five plan dates after the sheet's today, on or before
     *  its end, oldest first. */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every,
            Integer day, LocalDate end, int generated, List<LocalDate> nextDates) {
        static PlanView of(Plan plan, LocalDate today) {
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().toPlainString(), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getGeneratedCount(),
                    plan.datesAfter(today, 5));
        }
    }

    /** A plan's fields as entered on the person's row; no start means the sheet's today. A plan has either
     *  every (days) or day (of the month, for a monthly plan). */
    public record PlanForm(@NotNull Long personId, @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LocalDate start, Integer every, Integer day, @NotNull LocalDate end) {
    }

    /** How many claims a generate made, the claims, oldest first, and the sheet's plans as they are then. */
    public record GenerateView(int generated, List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /** The sheet's plans in the order they were added. */
    @GetMapping
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String id = sheetId.trim();
        LocalDate today = service.today(id);
        return service.list(id).stream().map((plan) -> PlanView.of(plan, today)).toList();
    }

    /** Add a plan to a person. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PlanForm form) {
        String id = sheetId.trim();
        return PlanView.of(service.add(id, SignedInUser.name(user), form.personId(), form.amount(),
                form.start(), form.every(), form.day(), form.end()), service.today(id));
    }

    /** Make the plans' claims due by the sheet's today. */
    @PostMapping("/generate")
    public GenerateView generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String id = sheetId.trim();
        List<ClaimView> claims = service.generate(id, SignedInUser.name(user)).stream().map(ClaimView::of).toList();
        LocalDate today = service.today(id);
        return new GenerateView(claims.size(), claims,
                service.list(id).stream().map((plan) -> PlanView.of(plan, today)).toList());
    }
}
