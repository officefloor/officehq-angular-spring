package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
     *  (two decimals); and its cap on each person's open claims in it (none: no cap); and each person's carried
     *  figure fixed then, by person id; and each person's opening balance for it, by person id; and whether it is locked. */
    public record PeriodView(long id, String name, String from, String to, boolean closed, boolean soft, String closedTotal,
            Map<Long, String> closedPersonTotals, Integer cap, Map<Long, String> closedPersonCarried,
            Map<Long, String> openings, boolean locked) {

        static PeriodView of(Period p) {
            Map<Long, String> people = new TreeMap<>();
            p.getClosedPersonTotals().forEach((id, total) -> people.put(id, total.toPlainString()));
            Map<Long, String> carried = new TreeMap<>();
            p.getClosedPersonCarried().forEach((id, c) -> carried.put(id, c.toPlainString()));
            Map<Long, String> openings = new TreeMap<>();
            p.getOpenings().forEach((id, o) -> openings.put(id, o.toPlainString()));
            return new PeriodView(p.getId(), p.getName(), p.getFromDate().toString(), p.getToDate().toString(),
                    p.isClosed(), p.isSoftClosed(), p.getClosedTotal() == null ? null : p.getClosedTotal().toPlainString(), people,
                    p.getCap(), carried, openings, p.isLocked());
        }
    }

    /** A period to add: its cap on each person's open claims in it is a whole number of 1 or more, or none. */
    public record PeriodRequest(@NotBlank @Size(max = 200) String name, @NotNull LocalDate from,
            @NotNull LocalDate to, @Min(1) Integer cap) {
    }

    /** The months to generate periods for: a blank from-date takes the sheet's today. */
    public record GenerateRequest(LocalDate from, @NotNull LocalDate to) {
    }

    /** The fiscal year to generate periods for, by the calendar year its first day falls in. */
    public record GenerateFiscalYearRequest(@NotNull @Min(1) @Max(9998) Integer year) {
    }

    /** The calendar year to generate the four quarters of. */
    public record GenerateQuartersRequest(@NotNull @Min(1) @Max(9999) Integer year) {
    }

    /** Opening balances to paste: lines of name,period,amount. */
    public record OpeningsRequest(@NotNull @Size(max = 100000) String text) {
    }

    /** How many lines of opening balances went through. */
    public record OpeningsView(int count) {
    }

    private final PeriodService service;
    private final OpeningService openings;
    private final AsOf asOf;

    public PeriodController(PeriodService service, OpeningService openings, AsOf asOf) {
        this.service = service;
        this.openings = openings;
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
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.from(), request.to(),
                request.cap()));
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

    /** Generate the twelve monthly periods of a fiscal year as the generator above does. The periods made. */
    @PostMapping("/generate-fiscal-year")
    public List<PeriodView> generateFiscalYear(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GenerateFiscalYearRequest request) {
        return service.generateFiscalYear(sheetId.trim(), user.trim(), request.year()).stream()
                .map(PeriodView::of).toList();
    }

    /** Generate the four calendar quarters of a year, named yyyy-Q1 to yyyy-Q4, leaving out any quarter whose
     *  name is taken or that overlaps a period. The periods made. */
    @PostMapping("/generate-quarters")
    public List<PeriodView> generateQuarters(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GenerateQuartersRequest request) {
        return service.generateQuarters(sheetId.trim(), user.trim(), request.year()).stream()
                .map(PeriodView::of).toList();
    }

    /** Paste opening balances, each line adding its amount to a person's carried figure for a period; refused
     *  whole when any line is wrong. How many lines went through. */
    @PostMapping("/openings")
    public OpeningsView pasteOpenings(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid OpeningsRequest request) {
        return new OpeningsView(openings.paste(sheetId.trim(), user.trim(), request.text()));
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

    /** Lock a period, so it cannot be reopened; by a steward only, and refused when it is already locked. */
    @PostMapping("/{periodId}/lock")
    public PeriodView lock(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.lock(sheetId.trim(), user.trim(), periodId, true));
    }

    /** Unlock a period; by a steward only, and refused when it is not locked. */
    @PostMapping("/{periodId}/unlock")
    public PeriodView unlock(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.lock(sheetId.trim(), user.trim(), periodId, false));
    }
}
