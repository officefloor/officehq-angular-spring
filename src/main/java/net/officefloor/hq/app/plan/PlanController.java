package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The plans on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class PlanController {

    /** A plan as the screen shows it, with how many claims it has made so far. */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated) {

        public static PlanView of(PlanService.Counted c) {
            Plan p = c.plan();
            return new PlanView(p.getId(), p.getPersonId(), p.getAmount().toPlainString(), p.getStart(), p.getEvery(),
                    p.getDay(), p.getEnd(), c.generated());
        }
    }

    /** A plan as the form submits it, with either an interval or a day of the month; a missing start takes the
     *  sheet's today. */
    public record PlanForm(@NotNull BigDecimal amount, LocalDate start, Integer every, Integer day,
            @NotNull LocalDate end) {
    }

    /** What a generate did: how many claims it made, the claims in the order made, and the sheet's plans. */
    public record GeneratedView(int made, List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/plans")
    public List<PlanView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return plans.list(sheetId).stream().map(PlanView::of).toList();
    }

    @PostMapping("/people/{personId}/plans")
    public PlanView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PlanForm request) {
        return PlanView.of(plans.add(sheetId, user, personId, request.amount(), request.start(), request.every(),
                request.day(), request.end()));
    }

    @PostMapping("/plans/generate")
    public GeneratedView generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        PlanService.Generated done = plans.generate(sheetId, user);
        return new GeneratedView(done.claims().size(), done.claims().stream().map(ClaimView::of).toList(),
                done.plans().stream().map(PlanView::of).toList());
    }
}
