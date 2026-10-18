package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /**
     * A period: its name and from and to dates, both inclusive, whether it is closed and, when it is,
     * its due-total fixed then (two decimals; null while open).
     */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            String closedTotal) {
        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().setScale(2).toPlainString());
        }
    }

    /** A name and both dates are required. */
    public record PeriodForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {}

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods, by from-date. */
    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    /** Add a period; one that ends before it starts, reuses a name or overlaps another is refused. */
    @PostMapping
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody PeriodForm form) {
        return PeriodView.of(service.add(sheetId, user, form.name(), form.from(), form.to()));
    }

    /** Close a period, fixing its due-total; one already closed is refused. */
    @PostMapping("/{periodId}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @PathVariable long periodId) {
        return PeriodView.of(service.close(sheetId, user, periodId));
    }
}
