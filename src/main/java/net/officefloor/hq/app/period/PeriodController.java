package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing, adding, generating, closing, soft-closing and reopening the periods on a sheet. */
@RestController
@RequestMapping("/api/periods")
public class PeriodController {

    public record ListPeriodsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /** A period's name has no spaces: it is written into the audit record as one field. */
    public record AddPeriodRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String name, @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    public record GeneratePeriodsRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    /** The fiscal year to generate, by the calendar year its first day falls in. */
    public record GenerateFiscalYearRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotBlank @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    public record ClosePeriodRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long id) {
    }

    /**
     * closedDue is the due-total fixed when the period was closed, closedDues each person's (by
     * person id); null while it is open. fy is the fiscal year its from-date falls in.
     */
    public record PeriodResponse(long id, String name, LocalDate from, LocalDate to, boolean closed,
            boolean softClosed, String closedDue, Map<String, String> closedDues, String fy) {
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
                period.isClosed() || period.isSoftClosed() ? closedDues(period) : null, years.get(period.getId())))
                .toList();
    }

    private static Map<String, String> closedDues(Period period) {
        Map<String, String> dues = new TreeMap<>();
        period.getClosedDues().forEach((person, due) -> dues.put(String.valueOf(person),
                due.setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        return dues;
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<PeriodResponse> list(@Valid @RequestBody ListPeriodsRequest req) {
        return responses(req.sheet().trim(), service.list(req.sheet().trim()));
    }

    @PostMapping("/add")
    public PeriodResponse add(@Valid @RequestBody AddPeriodRequest req) {
        return response(req.sheet().trim(), service.add(req.sheet().trim(), req.user().trim(), req.name().trim(), req.from(), req.to()));
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

    @PostMapping("/reopen")
    public PeriodResponse reopen(@Valid @RequestBody ClosePeriodRequest req) {
        return response(req.sheet().trim(), service.reopen(req.sheet().trim(), req.user().trim(), req.id()));
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
