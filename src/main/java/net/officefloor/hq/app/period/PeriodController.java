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
import net.officefloor.hq.app.SignedInUser;
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

    /** What the screen shows of a period: its name and its from and to dates, both inclusive, whether
     *  it is closed, whether it is soft-closed and, once closed or soft-closed, the due-total fixed then (null while open) and each person's
     *  due-total and carried figure fixed then, by person id (empty while open), its cap on each person's open claims (null for none),
     *  and each person's opening balance for it, by person id (a person missing has none). */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, String closedTotal, Map<Long, String> closedPersonTotals, Integer cap,
            Map<Long, String> closedPersonCarried, Map<Long, String> personOpenings) {
        static PeriodView of(Period period) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getClosedPersonTotals().forEach((id, total) -> personTotals.put(id, total.toPlainString()));
            Map<Long, String> personCarried = new TreeMap<>();
            period.getClosedPersonCarried().forEach((id, carried) -> personCarried.put(id, carried.toPlainString()));
            Map<Long, String> openings = new TreeMap<>();
            period.getPersonOpenings().forEach((id, amount) -> openings.put(id, amount.toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFrom(), period.getTo(),
                    period.isClosed(), period.isSoftClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().toPlainString(),
                    personTotals, period.getCap(), personCarried, openings);
        }
    }

    /** A period's fields, as entered on the form; a blank cap is none. */
    public record PeriodForm(@NotBlank @Size(max = 200) String name, @NotNull LocalDate from,
            @NotNull LocalDate to, @Min(1) Integer cap) {
    }

    /** The dates monthly periods are generated between; a blank date is the sheet's today. */
    public record GenerateForm(LocalDate from, LocalDate to) {
    }

    /** The calendar year a fiscal year starts in, as entered. */
    public record GenerateFiscalYearForm(@NotNull @Min(1) @Max(9998) Integer year) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods in date order. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PeriodView::of).toList();
    }

    /** Add a period to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PeriodForm form) {
        return PeriodView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.name(), form.from(),
                form.to(), form.cap()));
    }

    /** Generate one period per calendar month from the from-date's month to the to-date's month,
     *  leaving out those whose name is in use or whose days overlap a period; the periods made. */
    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody GenerateForm form) {
        return service.generate(sheetId.trim(), SignedInUser.name(user), form.from(), form.to()).stream()
                .map(PeriodView::of).toList();
    }

    /** Generate the twelve monthly periods of a fiscal year, as the monthly generator does; the periods made. */
    @PostMapping("/generate-fy")
    public List<PeriodView> generateFiscalYear(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid GenerateFiscalYearForm form) {
        return service.generateFiscalYear(sheetId.trim(), SignedInUser.name(user), form.year()).stream()
                .map(PeriodView::of).toList();
    }

    /** Close a period, fixing its due-total. */
    @PostMapping("/{id}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.close(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Soft-close a period, fixing its figures while its claims can still change. */
    @PostMapping("/{id}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.softClose(sheetId.trim(), SignedInUser.name(user), id));
    }

    /** Reopen a closed or soft-closed period, making its due-total live again; a steward only. */
    @PostMapping("/{id}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long id,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), SignedInUser.name(user), id));
    }
}
