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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** A period as the screen shows it; a closed one carries the due-total fixed when it was closed. */
    public record PeriodView(Long id, String name, LocalDate from, LocalDate to, boolean closed, String closedTotal) {
        static PeriodView of(Period p) {
            return new PeriodView(p.getId(), p.getName(), p.getFrom(), p.getTo(), p.isClosed(),
                    p.getClosedTotal() == null ? null : p.getClosedTotal().toPlainString());
        }
    }

    /** A period as the form submits it: a name and its from and to dates, inclusive. */
    public record PeriodForm(@NotBlank @Size(max = 100) String name,
            @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods, by from-date. */
    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PeriodForm form, @SessionAttribute(SessionController.USER) String user) {
        return PeriodView.of(service.add(user, sheetId, form.name(), form.from(), form.to()));
    }

    @PostMapping("/{id}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return PeriodView.of(service.close(user, sheetId, id));
    }
}
