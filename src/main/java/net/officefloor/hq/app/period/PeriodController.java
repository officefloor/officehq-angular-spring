package net.officefloor.hq.app.period;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import net.officefloor.hq.app.AsOf;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.history.History;
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
     *  when it was closed, and closedPersonCarried each person's carried figure for it fixed then, by
     *  person id (both empty while it is open); openings is each person's opening balance for it, by
     *  person id, added to their carried figure while it is open. */
    public record PeriodView(Long id, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, String closedTotal, Map<Long, String> closedPersonTotals, Integer cap,
            Map<Long, String> closedPersonCarried, Map<Long, String> openings, boolean locked) {
        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFrom(), period.getTo(),
                    period.isClosed(), period.isSoftClosed(),
                    period.getClosedTotal() != null ? period.getClosedTotal().toPlainString() : null,
                    texts(period.getPersonTotals()), period.getCap(), texts(period.getPersonCarried()),
                    texts(period.getPersonOpenings()), period.isLocked());
        }

        static PeriodView of(History.PeriodThen period, Integer cap, Map<Long, BigDecimal> openings,
                boolean locked) {
            return new PeriodView(period.id(), period.name(), period.from(), period.to(), period.closed(),
                    period.softClosed(), period.closedTotal() != null ? period.closedTotal().toPlainString() : null,
                    texts(period.personTotals()), cap, texts(period.personCarried()), texts(openings), locked);
        }

        private static Map<Long, String> texts(Map<Long, BigDecimal> figures) {
            Map<Long, String> texts = new TreeMap<>();
            figures.forEach((id, figure) -> texts.put(id, figure.toPlainString()));
            return texts;
        }
    }

    /** A period: a name, its from and to dates, and the most OPEN and PENDING claims a person may
     *  have in it (null: no cap). */
    public record PeriodRequest(@NotBlank @Size(max = 200) String name,
            @NotNull LocalDate from, @NotNull LocalDate to, @Min(1) Integer cap) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods, the earliest first: as they stand now, or as they stood at the end of the
     *  screen's as-of date. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        LocalDate asOf = AsOf.parse(asOfHeader);
        if (asOf != null) {
            // A period's cap is fixed when it is added, so the period as it stood then has the cap it has now;
            // its opening balances are those it holds now, and it is locked as it is now.
            Map<Long, Period> now = new HashMap<>();
            service.list(sheetId.trim()).forEach(period -> now.put(period.getId(), period));
            return service.listAsOf(sheetId.trim(), asOf).stream()
                    .map(period -> {
                        Period current = now.get(period.id());
                        return PeriodView.of(period, current != null ? current.getCap() : null,
                                current != null ? current.getPersonOpenings() : Map.of(),
                                current != null && current.isLocked());
                    }).toList();
        }
        return service.list(sheetId.trim()).stream().map(PeriodView::of).toList();
    }

    /** Add a period to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.name().trim(),
                request.from(), request.to(), request.cap()));
    }

    /** Monthly periods to generate: the dates whose months they run from and to; a blank date is the
     *  sheet's today. */
    public record GenerateRequest(LocalDate from, LocalDate to) {
    }

    /** How many periods a generate made. */
    public record GenerateView(int made) {
    }

    /** Generate one period per calendar month between two dates, leaving out months taken. */
    @PostMapping("/generate")
    public GenerateView generate(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody GenerateRequest request) {
        return new GenerateView(service.generate(sheetId.trim(), SignedInUser.decode(user), request.from(),
                request.to()));
    }

    /** The pasted lines of name,period,amount. */
    public record OpeningImportRequest(@NotNull @Size(max = 1_000_000) String text) {
    }

    /** How many opening lines an import added. */
    public record OpeningImportView(int added) {
    }

    /** Add each pasted opening balance to its person's carried figure for its period, or none when any
     *  line is refused. */
    @PostMapping("/openings")
    public OpeningImportView importOpenings(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated OpeningImportRequest request) {
        return new OpeningImportView(service.importOpenings(sheetId.trim(), SignedInUser.decode(user),
                request.text()));
    }

    /** A fiscal year to generate the monthly periods of: FY<year>. */
    public record GenerateFiscalYearRequest(@NotNull @Min(1) @Max(9998) Integer year) {
    }

    /** Generate the twelve monthly periods of a fiscal year, leaving out months taken. */
    @PostMapping("/generate-fy")
    public GenerateView generateFiscalYear(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated GenerateFiscalYearRequest request) {
        return new GenerateView(service.generateFiscalYear(sheetId.trim(), SignedInUser.decode(user),
                request.year()));
    }

    /** A calendar year to generate the quarterly periods of. */
    public record GenerateQuartersRequest(@NotNull @Min(1) @Max(9999) Integer year) {
    }

    /** Generate the four calendar quarters of a year as periods yyyy-Q1 to yyyy-Q4, leaving out
     *  quarters taken. */
    @PostMapping("/generate-quarters")
    public GenerateView generateQuarters(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated GenerateQuartersRequest request) {
        return new GenerateView(service.generateQuarters(sheetId.trim(), SignedInUser.decode(user),
                request.year()));
    }

    /** Close a period, fixing its due-total. */
    @PostMapping("/{name}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.close(sheetId.trim(), SignedInUser.decode(user), name));
    }

    /** Soft-close a period, fixing its figures while claims dated in it can still change. */
    @PostMapping("/{name}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.softClose(sheetId.trim(), SignedInUser.decode(user), name));
    }

    /** Reopen a closed or soft-closed period, making its due-total live again; only a steward may. */
    @PostMapping("/{name}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.reopen(sheetId.trim(), SignedInUser.decode(user), name));
    }

    /** Lock a period so it cannot be reopened; only a steward may. */
    @PostMapping("/{name}/lock")
    public PeriodView lock(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.lock(sheetId.trim(), SignedInUser.decode(user), name));
    }

    /** Unlock a locked period; only a steward may. */
    @PostMapping("/{name}/unlock")
    public PeriodView unlock(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable String name,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        return PeriodView.of(service.unlock(sheetId.trim(), SignedInUser.decode(user), name));
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

    /** A name taken, dates running backwards, a period overlapping another, a reopen by other than a
     *  steward or of a period not closed or locked, a lock or unlock by other than a steward or of a period
     *  already so, or a pasted opening line naming no person or period on the
     *  sheet or an amount below 0.00 or with more than two decimals is refused. */
    @ExceptionHandler({PeriodRefusedException.class, OpeningRefusedException.class,
            DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** A date that is not a real yyyy-mm-dd date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
