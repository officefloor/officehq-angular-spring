package net.officefloor.hq.app.period;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** Periods on a sheet. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** The closed total is sent as text with two decimals; null while the period is open. */
    public record PeriodView(Long id, String name, LocalDate from, LocalDate to, boolean closed, String closedTotal) {
        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFrom(), period.getTo(),
                    period.isClosed(), period.getClosedTotal() == null ? null : period.getClosedTotal().toPlainString());
        }
    }

    /** The name has no spaces (it names the period on screen and in the audit); both dates are inclusive. */
    public record PeriodRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String name,
            @NotNull LocalDate from, @NotNull LocalDate to) {
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
            @RequestBody @Validated PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.from(), request.to()));
    }

    @PostMapping("/{name}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId.trim(), user.trim(), name));
    }

    @PostMapping("/{name}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), user.trim(), name));
    }
}
