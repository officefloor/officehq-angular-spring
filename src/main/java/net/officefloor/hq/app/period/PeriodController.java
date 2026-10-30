package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The periods on a sheet, earliest first. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /**
     * A period as shown, whether it is closed or soft-closed; once either, with the due-total fixed then (null
     * while open), per person id the person's due-total fixed then and their carried figure fixed then (each
     * empty while open); per person id the opening balances pasted for them in it; and the fiscal year
     * (FY and a year) its from-date falls in.
     */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed, boolean softClosed,
            String closedTotal, Map<Long, String> personTotals, String fy, Integer cap,
            Map<Long, String> personCarried, Map<Long, String> personOpenings) {

        static PeriodView of(Period period, int fiscalStartMonth) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getPersonTotals().forEach((id, t) -> personTotals.put(id, t.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            Map<Long, String> personCarried = new TreeMap<>();
            period.getPersonCarried().forEach((id, t) -> personCarried.put(id, t.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            Map<Long, String> personOpenings = new TreeMap<>();
            period.getPersonOpenings().forEach((id, t) -> personOpenings.put(id, t.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(), period.isSoftClosed(), period.getClosedTotal() == null ? null
                            : period.getClosedTotal().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    personTotals, PeriodService.fiscalYear(period.getFromDate(), fiscalStartMonth), period.getCap(),
                    personCarried, personOpenings);
        }
    }

    /** A name, a from and to date, both inclusive, and the most OPEN or PENDING claims a person may have in it (null for none). */
    public record PeriodForm(@NotBlank @Size(max = 100) String name, @NotNull LocalDate from, @NotNull LocalDate to,
            Integer cap) {
    }

    public record Refusal(String message) {
    }

    /** Pasted lines of name,period,amount, an opening balance each. */
    public record OpeningsForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** How many pasted lines went through, and the sheet's periods as they now stand. */
    public record OpeningsResult(int added, List<PeriodView> periods) {
    }

    private final PeriodService service;
    private final OpeningImportService openings;

    public PeriodController(PeriodService service, OpeningImportService openings) {
        this.service = service;
        this.openings = openings;
    }

    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        return views(sheet, service.list(sheet));
    }

    @PostMapping
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PeriodForm form) {
        String sheet = sheetId.trim();
        return view(sheet, service.add(sheet, user.trim(), form.name().trim(), form.from(), form.to(), form.cap()));
    }

    /** A from and to date: one period per calendar month from the from-date's month to the to-date's. */
    public record GenerateForm(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody GenerateForm form) {
        String sheet = sheetId.trim();
        return views(sheet, service.generate(sheet, user.trim(), form.from(), form.to()));
    }

    /** A calendar year: the twelve monthly periods of the fiscal year whose first day falls in it. */
    public record GenerateFiscalYearForm(@NotNull @Min(1) @Max(9999) Integer year) {
    }

    @PostMapping("/generate-fy")
    public List<PeriodView> generateFiscalYear(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody GenerateFiscalYearForm form) {
        String sheet = sheetId.trim();
        return views(sheet, service.generateFiscalYear(sheet, user.trim(), form.year()));
    }

    /** Paste opening balances, a line each; one refused line adds none. */
    @PostMapping("/openings")
    public OpeningsResult importOpenings(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody OpeningsForm form) {
        String sheet = sheetId.trim();
        int added = openings.importOpenings(sheet, user.trim(), form.text());
        return new OpeningsResult(added, views(sheet, service.list(sheet)));
    }

    private PeriodView view(String sheet, Period period) {
        return PeriodView.of(period, service.fiscalStartMonth(sheet));
    }

    private List<PeriodView> views(String sheet, List<Period> periods) {
        int fiscalStartMonth = service.fiscalStartMonth(sheet);
        return periods.stream().map(p -> PeriodView.of(p, fiscalStartMonth)).toList();
    }

    @PostMapping("/{periodId}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, service.close(sheet, user.trim(), periodId));
    }

    @PostMapping("/{periodId}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, service.softClose(sheet, user.trim(), periodId));
    }

    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank String user) {
        String sheet = sheetId.trim();
        return view(sheet, service.reopen(sheet, user.trim(), periodId));
    }

    @ExceptionHandler(PeriodRefused.class)
    public ResponseEntity<Refusal> refused(PeriodRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user added a period with the same name at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
