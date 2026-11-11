package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing, adding, generating, rolling over, closing, soft-closing, reopening, locking and unlocking the periods on a sheet. */
@RestController
@RequestMapping("/api/periods")
public class PeriodController {

    public record ListPeriodsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /** A period's name has no spaces: it is written into the audit record as one field. */
    public record AddPeriodRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String name, @NotNull LocalDate from,
            @NotNull LocalDate to, @Positive Integer cap) {
    }

    public record GeneratePeriodsRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    /** The fiscal year to generate, by the calendar year its first day falls in. */
    public record GenerateFiscalYearRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotBlank @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    /** The calendar year whose quarters to generate. */
    public record GenerateQuartersRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotBlank @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    /** The fiscal year to roll over, by the calendar year its first day falls in. */
    public record RolloverRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    /** What a rollover did: how many periods it made, and the sheet's periods now. */
    public record RolloverResponse(int made, List<PeriodResponse> periods) {
    }

    public record UndoRolloverRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user) {
    }

    public record ClosePeriodRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long id) {
    }

    /** Lines of name,period,amount. */
    public record ImportOpeningsRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull @Size(max = 100_000) String text) {
    }

    /** What a paste of opening balances did: how many lines went through, and the sheet's periods now. */
    public record ImportOpeningsResponse(int count, List<PeriodResponse> periods) {
    }

    /**
     * closedDue is the due-total fixed when the period was closed, closedDues each person's (by
     * person id); null while it is open. fy is the fiscal year its from-date falls in; cap its
     * claim cap per person, null for none. closedCarried is each person's carried figure fixed when it
     * was closed (by person id); null while it is open. openings is each person's opening balance for
     * it (by person id), added to their carried figure into it. locked is whether a steward locked it.
     */
    public record PeriodResponse(long id, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, String closedDue, Map<String, String> closedDues, String fy, Integer cap,
            Map<String, String> closedCarried, Map<String, String> openings, boolean locked) {
    }

    private PeriodResponse response(String sheetId, Period period) {
        return responses(sheetId, List.of(period)).get(0);
    }

    private List<PeriodResponse> responses(String sheetId, List<Period> list) {
        Map<Long, String> years = service.fiscalYears(sheetId, list);
        return list.stream().map(period -> new PeriodResponse(period.getId(), period.getName(), period.getFromDate(),
                period.getToDate(), period.isClosed(), period.isSoftClosed(),
                period.getClosedDue() == null ? null
                        : period.getClosedDue().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                period.isClosed() || period.isSoftClosed() ? closedDues(period) : null, years.get(period.getId()),
                period.getClaimCap(), period.isClosed() || period.isSoftClosed() ? figures(period.getClosedCarried()) : null,
                figures(period.getOpenings()), period.isLocked()))
                .toList();
    }

    private static Map<String, String> closedDues(Period period) {
        return figures(period.getClosedDues());
    }

    private static Map<String, String> figures(Map<Long, BigDecimal> byPerson) {
        Map<String, String> dues = new TreeMap<>();
        byPerson.forEach((person, due) -> dues.put(String.valueOf(person),
                due.setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        return dues;
    }

    private final PeriodService service;
    private final OpeningImportService openings;
    private final RolloverService rollovers;

    public PeriodController(PeriodService service, OpeningImportService openings, RolloverService rollovers) {
        this.service = service;
        this.openings = openings;
        this.rollovers = rollovers;
    }

    @PostMapping("/list")
    public List<PeriodResponse> list(@Valid @RequestBody ListPeriodsRequest req) {
        return responses(req.sheet().trim(), service.list(req.sheet().trim()));
    }

    @PostMapping("/add")
    public PeriodResponse add(@Valid @RequestBody AddPeriodRequest req) {
        return response(req.sheet().trim(), service.add(req.sheet().trim(), req.user().trim(), req.name().trim(), req.from(), req.to(), req.cap()));
    }

    /** The periods made, by from-date. */
    @PostMapping("/generate")
    public List<PeriodResponse> generate(@Valid @RequestBody GeneratePeriodsRequest req) {
        return responses(req.sheet().trim(), service.generate(req.sheet().trim(), req.user().trim(), req.from(), req.to()));
    }

    /** The periods made for the fiscal year, by from-date. */
    @PostMapping("/generate-fy")
    public List<PeriodResponse> generateFiscalYear(@Valid @RequestBody GenerateFiscalYearRequest req) {
        return responses(req.sheet().trim(), service.generateFiscalYear(req.sheet().trim(), req.user().trim(),
                Integer.parseInt(req.year().trim())));
    }

    /** The quarterly periods made for the year, by from-date. */
    @PostMapping("/generate-quarters")
    public List<PeriodResponse> generateQuarters(@Valid @RequestBody GenerateQuartersRequest req) {
        return responses(req.sheet().trim(), service.generateQuarters(req.sheet().trim(), req.user().trim(),
                Integer.parseInt(req.year().trim())));
    }

    @PostMapping("/openings")
    public ImportOpeningsResponse importOpenings(@Valid @RequestBody ImportOpeningsRequest req) {
        String sheet = req.sheet().trim();
        int count = openings.importOpenings(sheet, req.user().trim(), req.text());
        return new ImportOpeningsResponse(count, responses(sheet, service.list(sheet)));
    }

    @PostMapping("/rollover")
    public RolloverResponse rollover(@Valid @RequestBody RolloverRequest req) {
        String sheet = req.sheet().trim();
        int made = rollovers.rollover(sheet, req.user().trim(), Integer.parseInt(req.year().trim())).size();
        return new RolloverResponse(made, responses(sheet, service.list(sheet)));
    }

    /** Undo the last rollover: the sheet's periods now. */
    @PostMapping("/rollover-undo")
    public List<PeriodResponse> undoRollover(@Valid @RequestBody UndoRolloverRequest req) {
        String sheet = req.sheet().trim();
        rollovers.undo(sheet, req.user().trim());
        return responses(sheet, service.list(sheet));
    }

    @PostMapping("/reopen")
    public PeriodResponse reopen(@Valid @RequestBody ClosePeriodRequest req) {
        return response(req.sheet().trim(), service.reopen(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/lock")
    public PeriodResponse lock(@Valid @RequestBody ClosePeriodRequest req) {
        return response(req.sheet().trim(), service.setLocked(req.sheet().trim(), req.user().trim(), req.id(), true));
    }

    @PostMapping("/unlock")
    public PeriodResponse unlock(@Valid @RequestBody ClosePeriodRequest req) {
        return response(req.sheet().trim(), service.setLocked(req.sheet().trim(), req.user().trim(), req.id(), false));
    }

    @PostMapping("/soft-close")
    public PeriodResponse softClose(@Valid @RequestBody ClosePeriodRequest req) {
        return response(req.sheet().trim(), service.softClose(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/close")
    public PeriodResponse close(@Valid @RequestBody ClosePeriodRequest req) {
        return response(req.sheet().trim(), service.close(req.sheet().trim(), req.user().trim(), req.id()));
    }
}
