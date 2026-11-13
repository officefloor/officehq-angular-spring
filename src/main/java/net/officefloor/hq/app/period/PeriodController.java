package net.officefloor.hq.app.period;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** A period as the screen shows it; closedPersonTotals is each person's due-total for it fixed
     *  when it was closed, by person id (empty while it is open). */
    public record PeriodView(Long id, String name, LocalDate from, LocalDate to, boolean closed,
            String closedTotal, Map<Long, String> closedPersonTotals) {
        static PeriodView of(Period period) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getPersonTotals().forEach((id, total) -> personTotals.put(id, total.toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFrom(), period.getTo(),
                    period.isClosed(),
                    period.getClosedTotal() != null ? period.getClosedTotal().toPlainString() : null,
                    personTotals);
        }
    }

    /** A period: a name and its from and to dates. */
    public record PeriodRequest(@NotBlank @Size(max = 200) String name,
            @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods, the earliest first. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PeriodView::of).toList();
    }

    /** Add a period to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.name().trim(),
                request.from(), request.to()));
    }

    /** Close a period, fixing its due-total. */
    @PostMapping("/{name}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.close(sheetId.trim(), SignedInUser.decode(user), name));
    }

    /** Reopen a closed period, making its due-total live again; only a steward may. */
    @PostMapping("/{name}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), SignedInUser.decode(user), name));
    }

    /** A period already closed is refused. */
    @ExceptionHandler(PeriodClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void closed() {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    /** A name taken, dates running backwards, a period overlapping another, or a reopen by other
     *  than a steward or of a period not closed is refused. */
    @ExceptionHandler({PeriodRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** A date that is not a real yyyy-mm-dd date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
