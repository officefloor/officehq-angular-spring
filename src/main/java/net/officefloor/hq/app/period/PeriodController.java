package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.web.server.ResponseStatusException;

/** The periods of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /**
     * A period as shown, its dates as yyyy-mm-dd; once closed, the due-total fixed then (null while open) and each
     * person's due-total for it fixed then, by person id (empty while open).
     */
    public record PeriodView(Long id, String name, String from, String to, boolean closed, String closedTotal,
            Map<Long, String> personTotals) {

        static PeriodView of(Period period) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getPersonTotals().forEach((person, total) -> personTotals.put(person, total.toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate().toString(),
                    period.getToDate().toString(), period.isClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().toPlainString(), personTotals);
        }
    }

    /** A name of no spaces, and the from and to dates as yyyy-mm-dd. */
    public record PeriodRequest(@NotBlank @Pattern(regexp = "\\s*\\S{1,100}\\s*") String name,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String from,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String to) {

        static LocalDate date(String value) {
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods in date order. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), request.name().trim(),
                PeriodRequest.date(request.from()), PeriodRequest.date(request.to())));
    }

    /** Close a period, fixing its due-total; refused when it is already closed. */
    @PostMapping("/{name}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId.trim(), user.trim(), name.trim()));
    }

    /** Reopen a closed period, making its due-total live again; refused for a non-steward or a period not closed. */
    @PostMapping("/{name}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), user.trim(), name.trim()));
    }
}
