package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.history.AsOf;
import org.springframework.format.annotation.DateTimeFormat;
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

/** The sheet's periods. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** A period as the screen shows it: its name, its from and to dates (YYYY-MM-DD), whether it is
     *  closed or soft-closed and, once either, the due-total fixed then and each person's fixed then, by person id
     *  (two decimals). */
    public record PeriodView(long id, String name, String from, String to, boolean closed, boolean soft, String closedTotal,
            Map<Long, String> closedPersonTotals) {

        static PeriodView of(Period p) {
            Map<Long, String> people = new TreeMap<>();
            p.getClosedPersonTotals().forEach((id, total) -> people.put(id, total.toPlainString()));
            return new PeriodView(p.getId(), p.getName(), p.getFromDate().toString(), p.getToDate().toString(),
                    p.isClosed(), p.isSoftClosed(), p.getClosedTotal() == null ? null : p.getClosedTotal().toPlainString(), people);
        }
    }

    public record PeriodRequest(@NotBlank @Size(max = 200) String name, @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    /** The months to generate periods for: a blank from-date takes the sheet's today. */
    public record GenerateRequest(LocalDate from, @NotNull LocalDate to) {
    }

    private final PeriodService service;
    private final AsOf asOf;

    public PeriodController(PeriodService service, AsOf asOf) {
        this.service = service;
        this.asOf = asOf;
    }

    /** The sheet's periods, by from-date.; as they stood at the end of a date when the screen views the
     *  sheet as of one. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return (asOf == null ? service.list(sheetId.trim()) : this.asOf.periods(sheetId.trim(), asOf)).stream()
                .map(PeriodView::of).toList();
    }

    /** Add a period; refused when its name is taken, its dates run backwards or it overlaps another. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.from(), request.to()));
    }

    /** Generate a period per calendar month from the from-date's month to the to-date's month, leaving out
     *  any month whose name is taken or that overlaps a period; refused when the to-date is before the
     *  from-date. The periods made. */
    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GenerateRequest request) {
        return service.generate(sheetId.trim(), user.trim(), request.from(), request.to()).stream()
                .map(PeriodView::of).toList();
    }

    /** Close a period (a soft-closed one too), fixing its due-total afresh; refused when it is already closed. */
    @PostMapping("/{periodId}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.close(sheetId.trim(), user.trim(), periodId));
    }

    /** Soft-close a period, fixing its figures while its claims can still change; refused when it is
     *  already closed or soft-closed. */
    @PostMapping("/{periodId}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.softClose(sheetId.trim(), user.trim(), periodId));
    }

    /** Reopen a closed or soft-closed period, making its due-total live again; by a steward only, and refused when it
     *  is open. */
    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), user.trim(), periodId));
    }
}
