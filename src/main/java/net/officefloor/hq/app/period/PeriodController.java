package net.officefloor.hq.app.period;

import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Periods on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** A period as the screen shows it; softClosed is whether it is soft-closed; closedTotal is its due-total fixed
     *  when it was closed or soft-closed (null while open), and closedPersonTotals each person's, by person id, fixed
     *  then (empty while open; a person missing has 0.00). */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed, boolean softClosed,
            String closedTotal, Map<Long, String> closedPersonTotals) {

        public static PeriodView of(Period period) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getClosedPersonTotals().forEach((id, total) -> personTotals.put(id, total.toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(), period.isSoftClosed(), period.getClosedTotal() == null ? null : period.getClosedTotal().toPlainString(),
                    personTotals);
        }
    }

    /** A period as the form submits it: a name, and its from and to dates, inclusive; a blank
     *  from-date takes the sheet's today. */
    public record PeriodForm(@NotBlank @Size(max = 100) String name, LocalDate from, @NotNull LocalDate to) {
    }

    /** A generate request: one period per calendar month from the from-date's month to the to-date's; a blank
     *  from-date takes the sheet's today. */
    public record GenerateForm(LocalDate from, @NotNull LocalDate to) {
    }

    /** What a generate made: how many periods, and the periods themselves. */
    public record GenerateView(int made, List<PeriodView> periods) {
    }

    private final PeriodService periods;
    private final SheetHistory history;

    public PeriodController(PeriodService periods, SheetHistory history) {
        this.history = history;
        this.periods = periods;
    }

    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.viewsAsOf(sheetId, asOf).periods();
        }
        return periods.list(sheetId).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodForm request) {
        return PeriodView.of(periods.add(sheetId, user, request.name(), request.from(), request.to()));
    }

    @PostMapping("/generate")
    public GenerateView generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateForm request) {
        List<PeriodView> made = periods.generate(sheetId, user, request.from(), request.to()).stream()
                .map(PeriodView::of).toList();
        return new GenerateView(made.size(), made);
    }

    @PostMapping("/{name}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(periods.close(sheetId, user, name));
    }

    @PostMapping("/{name}/soft-close")
    public PeriodView softClose(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(periods.softClose(sheetId, user, name));
    }

    @PostMapping("/{name}/reopen")
    public PeriodView reopen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(periods.reopen(sheetId, user, name));
    }
}
