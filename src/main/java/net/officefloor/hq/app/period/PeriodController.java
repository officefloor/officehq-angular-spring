package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /** What the screen shows of a period: its name, the days it runs from and to, inclusive, whether
     *  it is closed, and the due-total fixed when it was closed (null while it is open). */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            String closedTotal) {
        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().setScale(2).toPlainString());
        }
    }

    /** The period form: a required name, and the required days it runs from and to. */
    public record PeriodForm(@NotBlank @Size(max = 100) String name, @NotNull LocalDate from,
            @NotNull LocalDate to) {
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
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodForm form) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), form.name().trim(), form.from(), form.to()));
    }

    @PostMapping("/{periodId}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return PeriodView.of(service.close(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return PeriodView.of(service.reopen(sheetId.trim(), user.trim(), periodId));
    }
}
