package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetHistory;
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

/** The periods of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /** A period, with whether it is closed, whether that is only a soft close, and the due-total fixed when it was closed (null while it is
     * open) and each person's due-total for it and carried figure into it fixed then, by person id (empty while it is open), sent
     * as text with two decimals so they are never rounded on the way. */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, Integer cap, boolean closed,
            boolean soft, String closedTotal, Map<Long, String> closedPersonTotals,
            Map<Long, String> closedPersonCarried) {
        static PeriodView of(Period period) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getClosedPersonTotals().forEach((id, total) -> personTotals.put(id, text(total)));
            Map<Long, String> personCarried = new TreeMap<>();
            period.getClosedPersonCarried().forEach((id, carried) -> personCarried.put(id, text(carried)));
            return new PeriodView(period.getId(), period.getName(), period.getFrom(), period.getTo(), period.getCap(),
                    period.isClosed(), period.isSoft(), period.getClosedTotal() == null ? null : text(period.getClosedTotal()),
                    personTotals, personCarried);
        }

        private static String text(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    /** A period's name (not blank, no spaces inside, kept without the spaces around it) and its from
     * and to dates, both inclusive, and the most OPEN or PENDING claims each person may have in it: a
     * whole number of at least one, blank or missing for no cap. */
    public record PeriodRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String name,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @Pattern(regexp = "\\s*(\\d{1,9})?\\s*") String cap) {
        Integer capValue() {
            return cap == null || cap.isBlank() ? null : Integer.valueOf(cap.trim());
        }
    }

    /** The dates whose months periods are generated for, both inclusive; a missing one is the sheet's today. */
    public record GenerateRequest(LocalDate from, LocalDate to) {
    }

    /** A fiscal year, by the calendar year its first day falls in: four digits. */
    public record GenerateFiscalYearRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
        int yearValue() {
            return Integer.parseInt(year.trim());
        }
    }

    private final PeriodService service;

    private final SheetHistory history;

    public PeriodController(PeriodService service, SheetHistory history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<Period> periods = asOf == null ? service.list(sheetId.trim())
                : history.read(sheetId.trim(), asOf).periods();
        return periods.stream().map(PeriodView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.from(), request.to(),
                request.capValue()));
    }

    /** Generate one period per month between the dates: the periods made, in order. */
    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody GenerateRequest request) {
        return service.generate(sheetId.trim(), user.trim(), request.from(), request.to()).stream()
                .map(PeriodView::of).toList();
    }

    /** Generate the fiscal year's twelve monthly periods: the periods made, in order. */
    @PostMapping("/generate-fy")
    public List<PeriodView> generateFiscalYear(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateFiscalYearRequest request) {
        return service.generateFiscalYear(sheetId.trim(), user.trim(), request.yearValue()).stream()
                .map(PeriodView::of).toList();
    }

    @PostMapping("/{periodId}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.softClose(sheetId.trim(), user.trim(), periodId));
    }

    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long periodId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), user.trim(), periodId));
    }
}
