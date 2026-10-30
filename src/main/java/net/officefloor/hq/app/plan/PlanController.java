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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people's plans on a sheet, and generating their claims. The signed-in user's name comes in {@code X-User}. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class PlanController {

    /**
     * A plan to add: an amount with up to two decimals, a start, either every N days (at least 1) or a day of the
     * month (1 to 31), and an end.
     */
    public record PlanForm(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            @NotNull LocalDate start, @Pattern(regexp = "\\d{1,6}") String every,
            @Pattern(regexp = "\\d{1,2}") String day, @NotNull LocalDate end) {
    }

    /** A plan's pause: from the day it was paused up to the day it was resumed (null while still paused). */
    public record PauseView(LocalDate from, LocalDate to) {
    }

    /**
     * A plan on the sheet; its amount with two decimals, how many claims it has made so far, whether it is paused and
     * its pauses.
     */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, long generated, boolean paused, List<PauseView> pauses) {
    }

    /** How many claims a generate made. */
    public record Generated(int made) {
    }

    public record Refusal(String message) {
    }

    private final Plans plans;

    public PlanController(Plans plans) {
        this.plans = plans;
    }

    @GetMapping("/plans")
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return plans.list(sheetId.trim()).stream()
                .map(this::view)
                .toList();
    }

    private PlanView view(Plan p) {
        List<PauseView> pauses = plans.pauses(p).stream()
                .map(x -> new PauseView(x.getPausedOn(), x.getResumedOn()))
                .toList();
        return new PlanView(p.getId(), p.getPersonId(), p.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                p.getStart(), p.getEvery(), p.getDay(), p.getEnd(), plans.generated(p),
                pauses.stream().anyMatch(x -> x.to() == null), pauses);
    }

    /** Pause a plan on the sheet's today; answers with every plan on the sheet. */
    @PostMapping("/plans/{planId}/pause")
    public List<PlanView> pause(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long planId,
            @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        plans.pause(sheet, user.trim(), planId, false);
        return list(sheet);
    }

    /** Resume a paused plan on the sheet's today; answers with every plan on the sheet. */
    @PostMapping("/plans/{planId}/resume")
    public List<PlanView> resume(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long planId,
            @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        plans.pause(sheet, user.trim(), planId, true);
        return list(sheet);
    }

    /** Add a plan to a person; answers with every plan on the sheet. */
    @PostMapping("/people/{personId}/plans")
    public List<PlanView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PlanForm form) {
        String sheet = sheetId.trim();
        plans.add(sheet, user.trim(), personId, new BigDecimal(form.amount()), form.start(),
                form.every() == null ? null : Integer.valueOf(form.every()),
                form.day() == null ? null : Integer.valueOf(form.day()), form.end());
        return list(sheet);
    }

    /** Make the claims every plan on the sheet is due up to the sheet's today. */
    @PostMapping("/plans/generate")
    public Generated generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user) {
        return new Generated(plans.generate(sheetId.trim(), user.trim()));
    }

    @ExceptionHandler(PlanRefused.class)
    public ResponseEntity<Refusal> refused(PlanRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }
}
