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
     * the month, the other null), and how many claims it has made.
     */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated) {

        static PlanView of(Plan plan) {
            return new PlanView(plan.getId(), plan.getPersonId(), plan.getAmount().toPlainString(), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), plan.getGenerated());
        }
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
        return service.list(sheetId).stream().map(PlanView::of).toList();
    }

    /** Add a plan on a person. */
    @PostMapping("/people/{personId}/plans")
    public PlanView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PlanForm form) {
        return PlanView.of(service.add(sheetId, SignedIn.name(user), personId, new BigDecimal(form.amount()),
                form.start(), number(form.every()), number(form.day()), form.end()));
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
        return new GeneratedView(generated.claims().size(), generated.claims().stream().map(ClaimView::of).toList(),
                generated.plans().stream().map(PlanView::of).toList());
    }
}
