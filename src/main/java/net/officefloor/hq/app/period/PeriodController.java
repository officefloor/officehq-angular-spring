package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.SignedIn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /**
     * What the screen is told about a period: its name, its inclusive from and to dates, whether it
     * is closed, the due-total fixed when it was closed (null while open), and each person's due-total
     * for it fixed then, by person id (empty while open).
     */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            String closedTotal, Map<Long, String> personTotals) {

        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().toPlainString(),
                    period.getPersonTotals().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toPlainString())));
        }
    }

    /** A period as entered on the form: a name, and a from and to date, both required. */
    public record PeriodForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods by from-date. */
    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    /** Add a period to the sheet. */
    @PostMapping
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PeriodForm form) {
        return PeriodView.of(service.add(sheetId, SignedIn.name(user), form.name(), form.from(), form.to()));
    }

    /** A range of months to generate periods for: a from and a to date, both required. */
    public record GenerateForm(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    /** Generate one period per calendar month in the range; the periods made, in month order. */
    @PostMapping("/generate")
    public List<PeriodView> generate(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid GenerateForm form) {
        return service.generate(sheetId, SignedIn.name(user), form.from(), form.to()).stream()
                .map(PeriodView::of).toList();
    }

    /** Close a period, fixing its due-total. */
    @PostMapping("/{periodId}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long periodId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.close(sheetId, SignedIn.name(user), periodId));
    }

    /** Reopen a closed period, making its due-total live again; a steward only. */
    @PostMapping("/{periodId}/reopen")
    public PeriodView reopen(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long periodId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        return PeriodView.of(service.reopen(sheetId, SignedIn.name(user), periodId));
    }

    /** Another user took the name between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void nameTaken() {
    }
}
