package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.session.CurrentUser;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /** A period as the screen shows it: whether it is closed or soft-closed, the due-total fixed when it was
     *  closed (null while open), each person's due-total for it fixed then, by person id
     *  (empty while open), and each person's carried figure for it fixed then, by person id (null
     *  while open, or when closed before carried figures were fixed), and each person's opening
     *  balance for it, by person id, and whether it is locked. */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed, boolean softClosed,
            String closedTotal, Integer cap,
            Map<Long, String> closedPersonTotals, Map<Long, String> closedPersonCarried, Map<Long, String> openings,
            boolean locked) {
        static PeriodView of(Period p) {
            return of(p.getId(), p);
        }

        /** The period of the id, which a version rebuilt from the sheet's history does not carry. */
        static PeriodView of(long id, Period p) {
            Map<Long, String> personTotals = new LinkedHashMap<>();
            p.getClosedPersonTotals().forEach((personId, total) -> personTotals.put(personId, total.setScale(2).toPlainString()));
            Map<Long, String> personCarried = null;
            if (p.isCarriedFixed()) {
                Map<Long, String> fixed = new LinkedHashMap<>();
                p.getClosedPersonCarried().forEach((personId, carried) -> fixed.put(personId, carried.setScale(2).toPlainString()));
                personCarried = fixed;
            }
            Map<Long, String> openings = new LinkedHashMap<>();
            p.getOpenings().forEach((personId, amount) -> openings.put(personId, amount.setScale(2).toPlainString()));
            return new PeriodView(id, p.getName(), p.getFrom(), p.getTo(), p.isClosed(), p.isSoftClosed(),
                    p.getClosedTotal() == null ? null : p.getClosedTotal().setScale(2).toPlainString(), p.getCap(), personTotals,
                    personCarried, openings, p.isLocked());
        }
    }

    /** A period as the form submits it: a name without spaces, an optional from-date (the sheet's
     *  today when absent), a to-date and an optional cap on each person's open claims in it (1 or more). */
    public record PeriodForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String name,
            LocalDate from,
            @NotNull LocalDate to,
            @Min(1) Integer cap) {
    }

    private final PeriodService service;
    private final HistoryService history;

    public PeriodController(PeriodService service, HistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.asOf(sheetId, asOf).periods().entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<Long, Period> e) -> e.getValue().getFrom()))
                    .map(e -> PeriodView.of(e.getKey(), e.getValue())).toList();
        }
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodForm body) {
        return PeriodView.of(service.add(sheetId, user.trim(), body.name(), body.from(), body.to(), body.cap()));
    }

    /** A month range as the generate form submits it: an optional from-date (the sheet's today
     *  when absent) and a to-date. */
    public record GenerateForm(LocalDate from, @NotNull LocalDate to) {
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PeriodView> generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateForm body) {
        return service.generate(sheetId, user.trim(), body.from(), body.to()).stream().map(PeriodView::of).toList();
    }

    /** A fiscal year as the generate form submits it: the calendar year (yyyy) its first day falls in. */
    public record GenerateFiscalYearForm(@NotNull @Pattern(regexp = "\\d{4}") String year) {
    }

    @PostMapping("/generate-fy")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PeriodView> generateFiscalYear(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateFiscalYearForm body) {
        return service.generateFiscalYear(sheetId, user.trim(), Integer.parseInt(body.year())).stream()
                .map(PeriodView::of).toList();
    }

    /** A calendar year as the generate-quarters form submits it (yyyy). */
    public record GenerateQuartersForm(@NotNull @Pattern(regexp = "\\d{4}") String year) {
    }

    @PostMapping("/generate-quarters")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PeriodView> generateQuarters(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateQuartersForm body) {
        return service.generateQuarters(sheetId, user.trim(), Integer.parseInt(body.year())).stream()
                .map(PeriodView::of).toList();
    }

    /** Opening balances as the paste submits them: lines of name,period,amount. */
    public record OpeningsForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** The periods an opening paste changed and how many lines went through. */
    public record OpeningsView(int imported, List<PeriodView> periods) {
    }

    @PostMapping("/openings")
    public OpeningsView importOpenings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody OpeningsForm body) {
        PeriodService.Openings done = service.importOpenings(sheetId, user.trim(), body.text());
        return new OpeningsView(done.count(), done.periods().stream().map(PeriodView::of).toList());
    }

    @PostMapping("/{id}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId, user.trim(), id));
    }

    @PostMapping("/{id}/soft-close")
    public PeriodView softClose(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.softClose(sheetId, user.trim(), id));
    }

    @PostMapping("/{id}/reopen")
    public PeriodView reopen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId, user.trim(), id));
    }

    @PostMapping("/{id}/lock")
    public PeriodView lock(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.lock(sheetId, user.trim(), id, true));
    }

    @PostMapping("/{id}/unlock")
    public PeriodView unlock(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.lock(sheetId, user.trim(), id, false));
    }
}
