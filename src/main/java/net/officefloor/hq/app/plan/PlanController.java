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
     * month (the other null), and how many claims it has made.
     */
    public record PlanView(Long id, Long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, long generated) {

        static PlanView of(Plan plan, long generated) {
            return new PlanView(plan.getId(), plan.getPersonId(),
                    plan.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), plan.getStart(),
                    plan.getEvery(), plan.getDay(), plan.getEnd(), generated);
        }
    }

    /**
     * An amount with up to two decimals, a start and end date, and either the interval in whole days or the day of the
     * month (the other blank or left out).
     */
    public record PlanRequest(
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            @NotNull LocalDate start,
            @Pattern(regexp = "\\s*(\\d{1,6})?\\s*") String every,
            @Pattern(regexp = "\\s*(\\d{1,6})?\\s*") String day,
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
        return service.list(sheetId.trim()).stream()
                .map(plan -> PlanView.of(plan, generated.getOrDefault(plan.getId(), 0L)))
                .toList();
    }

    @PostMapping("/people/{personId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PlanRequest request) {
        return PlanView.of(service.add(sheetId.trim(), user.trim(), personId, request.amountValue(), request.start(),
                request.everyValue(), request.dayValue(), request.end()), 0);
    }

    /** Make the claims of every plan date due and not yet made. */
    @PostMapping("/plans/generate")
    public GenerateResult generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new GenerateResult(service.generate(sheetId.trim(), user.trim()));
    }
}
