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
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class PlanController {

    /**
     * A plan as the screen shows it, with how many claims it has made so far and its next five plan dates after the
     * sheet's today.
     */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated, List<LocalDate> nextDates) {
        static PlanView of(Plan p, LocalDate today) {
            return new PlanView(p.getId(), p.getPersonId(), p.getAmount().toPlainString(), p.getStart(),
                    p.getEvery(), p.getDay(), p.getEnd(), p.getGenerated(), p.nextDates(today, 5));
        }
    }

    public record PlanForm(@NotNull BigDecimal amount, @NotNull LocalDate start, Integer every, Integer day,
            @NotNull LocalDate end) {
    }

    /** The claims a generate made, in the order made, and the sheet's plans as they stand. */
    public record GeneratedView(List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService service;
    private final SheetService sheets;

    public PlanController(PlanService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    /** The sheet's plans in the order they were added. */
    @GetMapping("/plans")
    public List<PlanView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        LocalDate today = sheets.load(sheetId).getToday();
        return service.list(sheetId).stream().map(p -> PlanView.of(p, today)).toList();
    }

    @PostMapping("/people/{personId}/plans")
    public PlanView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long personId, @Valid @RequestBody PlanForm form,
            @SessionAttribute(SessionController.USER) String user) {
        Plan added = service.add(user, sheetId, personId, form.amount(), form.start(), form.every(), form.day(),
                form.end());
        return PlanView.of(added, sheets.load(sheetId).getToday());
    }

    /** Makes the plans' claims up to the sheet's today. */
    @PostMapping("/plans/generate")
    public GeneratedView generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @SessionAttribute(SessionController.USER) String user) {
        PlanService.Generated g = service.generate(user, sheetId);
        LocalDate today = sheets.load(sheetId).getToday();
        return new GeneratedView(g.claims().stream().map(ClaimView::of).toList(),
                g.plans().stream().map(p -> PlanView.of(p, today)).toList());
    }
}
