package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The periods on a sheet, earliest first. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /**
     * A period as shown; once closed, with the due-total fixed then (null while open) and per person id the
     * person's due-total fixed then (empty while open).
     */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed, String closedTotal,
            Map<Long, String> personTotals) {

        static PeriodView of(Period period) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getPersonTotals().forEach((id, t) -> personTotals.put(id, t.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(), period.getClosedTotal() == null ? null
                            : period.getClosedTotal().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    personTotals);
        }
    }

    /** A name, and a from and to date, both inclusive. */
    public record PeriodForm(@NotBlank @Size(max = 100) String name, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    public record Refusal(String message) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PeriodForm form) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), form.name().trim(), form.from(), form.to()));
    }

    /** A from and to date: one period per calendar month from the from-date's month to the to-date's. */
    public record GenerateForm(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody GenerateForm form) {
        return service.generate(sheetId.trim(), user.trim(), form.from(), form.to()).stream().map(PeriodView::of).toList();
    }

    @PostMapping("/{periodId}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank String user) {
        return PeriodView.of(service.close(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), user.trim(), periodId));
    }

    @ExceptionHandler(PeriodRefused.class)
    public ResponseEntity<Refusal> refused(PeriodRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user added a period with the same name at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
