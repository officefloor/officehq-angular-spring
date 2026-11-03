package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.sheet.AsOfGuard;
import net.officefloor.hq.app.sheet.SheetService;
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

    /** What the screen shows of a period: its name, the days it runs from and to, inclusive, whether
     *  it is closed or soft-closed, the due-total fixed when it was closed (null while it is open), and each
     *  person's due-total for it fixed then, by the person's id (empty while it is open), and the
     *  fiscal year its from-date falls in (FYyyyy), and the most open or pending claims a person may
     *  have in it (null: no cap), and each person's carried figure for it fixed when it was closed, by
     *  the person's id (empty while it is open), and each person's opening balance for it, by the
     *  person's id (a person missing has 0.00), and whether a steward has locked it. */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, String closedTotal, Map<Long, String> closedPersonTotals, String fy, Integer cap,
            Map<Long, String> closedPersonCarried, Map<Long, String> personOpenings, boolean locked) {
        static PeriodView of(Period period, int fiscalStartMonth) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getPersonTotals().forEach((id, total) -> personTotals.put(id, total.setScale(2).toPlainString()));
            Map<Long, String> personCarried = new TreeMap<>();
            period.getPersonCarried().forEach((id, carried) -> personCarried.put(id, carried.setScale(2).toPlainString()));
            Map<Long, String> personOpenings = new TreeMap<>();
            period.getPersonOpenings().forEach((id, opening) -> personOpenings.put(id, opening.setScale(2).toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(), period.isSoftClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().setScale(2).toPlainString(),
                    personTotals, PeriodService.fiscalYear(period.getFromDate(), fiscalStartMonth),
                    period.getClaimCap(), personCarried, personOpenings, period.isLocked());
        }
    }

    /** The period form: a required name, the required days it runs from and to, and the most open
     *  or pending claims a person may have in it (a whole number of 1 or more; null for no cap). */
    public record PeriodForm(@NotBlank @Size(max = 100) String name, @NotNull LocalDate from,
            @NotNull LocalDate to, @Min(1) Integer cap) {
    }

    /** The generate form: the day whose month the periods start from (the sheet's today when none
     *  is given), and the required day whose month they end with. */
    public record GenerateForm(LocalDate from, @NotNull LocalDate to) {
    }

    /** The generate fiscal year form: the required year (yyyy) the fiscal year is named by. */
    public record GenerateFyForm(@NotBlank @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {

        int yearValue() {
            return Integer.parseInt(year.trim());
        }
    }

    private final PeriodService service;
    private final SheetService sheets;
    private final History history;

    public PeriodController(PeriodService service, SheetService sheets, History history) {
        this.service = service;
        this.sheets = sheets;
        this.history = history;
    }

    /** The view of a period on the sheet. */
    private PeriodView view(String sheetId, Period period) {
        return PeriodView.of(period, sheets.fiscalStartMonth(sheetId));
    }

    /** The periods on the sheet, earliest first; as of a day when one is given, as they stood at the
     *  end of it (see {@link History}). */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        List<Period> periods = asOf != null ? history.asOf(sheetId.trim(), asOf).periods()
                : service.list(sheetId.trim());
        int fiscalStartMonth = sheets.fiscalStartMonth(sheetId.trim());
        return periods.stream().map(p -> PeriodView.of(p, fiscalStartMonth)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodForm form) {
        return view(sheetId.trim(), service.add(sheetId.trim(), user.trim(), form.name().trim(), form.from(), form.to(), form.cap()));
    }

    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateForm form) {
        int fiscalStartMonth = sheets.fiscalStartMonth(sheetId.trim());
        return service.generate(sheetId.trim(), user.trim(), form.from(), form.to()).stream()
                .map(p -> PeriodView.of(p, fiscalStartMonth)).toList();
    }

    @PostMapping("/generate-fy")
    public List<PeriodView> generateFy(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateFyForm form) {
        int fiscalStartMonth = sheets.fiscalStartMonth(sheetId.trim());
        return service.generateFiscalYear(sheetId.trim(), user.trim(), form.yearValue()).stream()
                .map(p -> PeriodView.of(p, fiscalStartMonth)).toList();
    }

    @PostMapping("/{periodId}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return view(sheetId.trim(), service.close(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return view(sheetId.trim(), service.softClose(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return view(sheetId.trim(), service.reopen(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/lock")
    public PeriodView lock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return view(sheetId.trim(), service.lock(sheetId.trim(), user.trim(), periodId, true));
    }

    @PostMapping("/{periodId}/unlock")
    public PeriodView unlock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user, @PathVariable long periodId) {
        return view(sheetId.trim(), service.lock(sheetId.trim(), user.trim(), periodId, false));
    }
}
