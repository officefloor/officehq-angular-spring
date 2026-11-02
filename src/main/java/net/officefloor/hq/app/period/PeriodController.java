package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.setting.OpenToViewer;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /**
     * A period as the screen shows it; once closed, with the due-total fixed then and each person's
     * due-total and carried figure for it fixed then, by person id (two decimals; empty while open). Soft-closed, the
     * figures are fixed the same way while it is not closed. Locked, it cannot be reopened.
     */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, String closedDueTotal, Map<Long, String> closedPersonDues, Integer cap,
            Map<Long, String> closedPersonCarried, Map<Long, String> openings, boolean locked) {
        static PeriodView of(Period period) {
            Map<Long, String> personDues = new TreeMap<>();
            period.getClosedPersonDues().forEach((personId, due) -> personDues.put(personId, due.setScale(2).toPlainString()));
            Map<Long, String> carried = new TreeMap<>();
            period.getClosedPersonCarried().forEach((personId, due) -> carried.put(personId, due.setScale(2).toPlainString()));
            Map<Long, String> openings = new TreeMap<>();
            period.getOpenings().forEach((personId, amount) -> openings.put(personId, amount.setScale(2).toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(), period.isSoftClosed(),
                    period.getClosedDueTotal() == null ? null : period.getClosedDueTotal().setScale(2).toPlainString(),
                    personDues, period.getCap(), carried, openings, period.isLocked());
        }
    }

    /**
     * A name of letters, digits, '.', '_' or '-', a from and to date, both inclusive, and a cap on each
     * person's OPEN or PENDING claims in it (null: none).
     */
    public record PeriodRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String name,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @Positive Integer cap) {}

    /** The first and last dates whose months get a period each; blank takes the sheet's today. */
    public record GenerateRequest(LocalDate from, LocalDate to) {}

    /** A fiscal year by the calendar year its first day falls in (yyyy). */
    public record GenerateFiscalYearRequest(@NotNull @Pattern(regexp = "\\d{4}") String year) {}

    /** A calendar year whose quarters get a period each (yyyy). */
    public record GenerateQuartersRequest(@NotNull @Pattern(regexp = "\\d{4}") String year) {}

    /** Lines of name,period,amount to add to people's opening balances. */
    public record OpeningImportRequest(@NotNull @Size(max = 100_000) String text) {}

    /** How many lines an opening import took, and the periods it changed as the store now holds them. */
    public record OpeningImportView(int lines, List<PeriodView> periods) {}

    /** How many periods a rollover made, and the sheet's periods as the store now holds them. */
    public record RolloverView(int made, List<PeriodView> periods) {}

    private final PeriodService service;
    private final OpeningImportService openings;

    public PeriodController(PeriodService service, OpeningImportService openings) {
        this.service = service;
        this.openings = openings;
    }

    @GetMapping
    public List<PeriodView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    public PeriodView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PeriodRequest request) {
        return PeriodView.of(service.add(sheetId, user, request.name(), request.from(), request.to(), request.cap()));
    }

    /** Make a period for each month from the from-date's to the to-date's: those made, earliest first. */
    @OpenToViewer
    @PostMapping("/generate")
    public List<PeriodView> generate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody GenerateRequest request) {
        return service.generate(sheetId, user, request.from(), request.to()).stream().map(PeriodView::of).toList();
    }

    /** Make the fiscal year's twelve monthly periods as generate does: those made, earliest first. */
    @PostMapping("/generate-fy")
    public List<PeriodView> generateFiscalYear(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateFiscalYearRequest request) {
        return service.generateFiscalYear(sheetId, user, Integer.parseInt(request.year())).stream()
                .map(PeriodView::of).toList();
    }

    /**
     * Roll the fiscal year over: the next one's periods made and each person's open figure at the old year's
     * end added to their opening balance for its first period. The periods made, and the new first period
     * as the store now holds it, by count made.
     */
    @PostMapping("/rollover")
    public RolloverView rollover(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateFiscalYearRequest request) {
        int made = service.rollover(sheetId, user, Integer.parseInt(request.year())).size();
        return new RolloverView(made, service.list(sheetId).stream().map(PeriodView::of).toList());
    }

    /** Undo the sheet's last rollover: the sheet's periods as the store now holds them. */
    @PostMapping("/rollover/undo")
    public List<PeriodView> undoRollover(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        service.undoRollover(sheetId, user);
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    /** Make the calendar year's four quarterly periods, leaving out taken or overlapped ones: those made, earliest first. */
    @PostMapping("/generate-quarters")
    public List<PeriodView> generateQuarters(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateQuartersRequest request) {
        return service.generateQuarters(sheetId, user, Integer.parseInt(request.year())).stream()
                .map(PeriodView::of).toList();
    }

    /** Add each pasted line's amount to the person's opening balance for the period; any bad line refuses them all. */
    @PostMapping("/openings")
    public OpeningImportView importOpenings(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid OpeningImportRequest request) {
        OpeningImportService.Result result = openings.importOpenings(sheetId, user, request.text());
        return new OpeningImportView(result.lines(), result.periods().stream().map(PeriodView::of).toList());
    }

    @PostMapping("/{id}/close")
    public PeriodView close(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId, user, id));
    }

    @PostMapping("/{id}/soft-close")
    public PeriodView softClose(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.softClose(sheetId, user, id));
    }

    @PostMapping("/{id}/reopen")
    public PeriodView reopen(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId, user, id));
    }

    @PostMapping("/{id}/lock")
    public PeriodView lock(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.lock(sheetId, user, id, true));
    }

    @PostMapping("/{id}/unlock")
    public PeriodView unlock(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.lock(sheetId, user, id, false));
    }
}
