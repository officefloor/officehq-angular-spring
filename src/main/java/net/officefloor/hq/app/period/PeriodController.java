package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The periods of a sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** The from and to dates are both inclusive. */
    public record PeriodForm(@NotBlank @Size(max = 200) String name, @NotNull LocalDate from, @NotNull LocalDate to) {}

    /** A closed period carries the due-total it closed at. */
    public record PeriodView(Long id, String name, LocalDate from, LocalDate to, boolean closed, String closedTotal) {

        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFrom(), period.getTo(),
                    period.isClosed(),
                    period.getClosedTotal() != null ? period.getClosedTotal().setScale(2).toPlainString() : null);
        }
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    @GetMapping
    public List<PeriodView> list(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    public PeriodView add(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody PeriodForm form) {
        return PeriodView.of(service.add(user, sheetId, form.name(), form.from(), form.to()));
    }

    @PostMapping("/{id}/close")
    public PeriodView close(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return PeriodView.of(service.close(user, sheetId, id));
    }
}
