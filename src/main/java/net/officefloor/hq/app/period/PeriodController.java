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
import java.util.function.Function;
import net.officefloor.hq.app.role.RoleService;
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
     * person's due-total for it fixed then, by person id (empty while open), and each person's carried figure fixed
     * then, likewise; the fiscal year (FYyyyy) its from-date
     * falls in; each person's opening balance for it, by person id, added to their carried figure; and whether a
     * steward has locked it; and each pool member's cut fixed when it was closed or soft-closed (empty while open).
     */
    public record PeriodView(Long id, String name, String from, String to, boolean closed, boolean softClosed,
            String closedTotal,
            Map<Long, String> personTotals, String fy, Integer cap, Map<Long, String> personCarried,
            Map<Long, String> personOpenings, boolean locked, List<PoolCutView> poolCuts) {

        static PeriodView of(Period period, Function<LocalDate, String> fiscalYears) {
            Map<Long, String> personTotals = new TreeMap<>();
            period.getPersonTotals().forEach((person, total) -> personTotals.put(person, total.toPlainString()));
            Map<Long, String> personCarried = new TreeMap<>();
            period.getPersonCarried().forEach((person, carried) -> personCarried.put(person, carried.toPlainString()));
            Map<Long, String> personOpenings = new TreeMap<>();
            period.getPersonOpenings().forEach((person, opening) -> personOpenings.put(person, opening.toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate().toString(),
                    period.getToDate().toString(), period.isClosed(), period.isSoftClosed(),
                    period.getClosedTotal() == null ? null : period.getClosedTotal().toPlainString(), personTotals,
                    fiscalYears.apply(period.getFromDate()), period.getCap(), personCarried,
                    personOpenings, period.isLocked(),
                    period.getPoolCuts().stream().map(c -> new PoolCutView(c.getPoolId(), c.getMember(),
                            c.getCut().toPlainString())).toList());
        }
    }

    /** A pool member's fixed cut: the pool's id (a crew's negated), the member as person:<id> or pool:<id>, the cut. */
    public record PoolCutView(Long poolId, String member, String cut) {
    }

    /**
     * A name of no spaces, the from and to dates as yyyy-mm-dd, and the cap on each person's OPEN or PENDING claims in
     * it as a whole number of 1 or more (null or blank for none).
     */
    public record PeriodRequest(@NotBlank @Pattern(regexp = "\\s*\\S{1,100}\\s*") String name,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String from,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String to,
            @Pattern(regexp = "\\s*(0*[1-9]\\d{0,8})?\\s*") String cap) {

        Integer capValue() {
            return cap == null || cap.isBlank() ? null : Integer.valueOf(cap.trim());
        }

        static LocalDate date(String value) {
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    private final PeriodService service;
    private final OpeningImportService openings;
    private final RolloverService rollovers;
    private final RoleService roles;

    public PeriodController(PeriodService service, OpeningImportService openings, RolloverService rollovers,
            RoleService roles) {
        this.roles = roles;
        this.service = service;
        this.openings = openings;
        this.rollovers = rollovers;
    }

    /** The sheet's periods in date order. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return views(sheetId.trim(), service.list(sheetId.trim()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PeriodRequest request) {
        return view(sheetId.trim(), service.add(sheetId.trim(), user.trim(), request.name().trim(),
                PeriodRequest.date(request.from()), PeriodRequest.date(request.to()), request.capValue()));
    }

    /** The from and to dates of a generate, as yyyy-mm-dd. */
    public record GenerateRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String from,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String to) {
    }

    /**
     * Generate a period per calendar month between the dates; the periods made, none for a viewer. Refused when to is
     * before from.
     */
    @PostMapping("/generate")
    public List<PeriodView> generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateRequest request) {
        if (roles.isViewer(sheetId.trim(), user.trim())) {
            return List.of();
        }
        return views(sheetId.trim(), service.generate(sheetId.trim(), user.trim(), PeriodRequest.date(request.from()),
                PeriodRequest.date(request.to())));
    }

    /** A fiscal year to generate, as yyyy. */
    public record GenerateFiscalYearRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    /** Generate the twelve monthly periods of the fiscal year, as a generate over its dates would; the periods made. */
    @PostMapping("/generate-fy")
    public List<PeriodView> generateFiscalYear(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateFiscalYearRequest request) {
        String sheet = sheetId.trim();
        List<Period> made = service.generateFiscalYear(sheet, user.trim(), Integer.parseInt(request.year().trim()));
        return views(sheet, made);
    }

    /** A fiscal year to roll over, as yyyy. */
    public record RolloverRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    /**
     * Roll the fiscal year over: generate the next fiscal year's periods and carry each person's open figure at the
     * old year's end into the new year's first period; the periods made. Refused for a non-steward or a year with no
     * periods.
     */
    @PostMapping("/rollover")
    public List<PeriodView> rollover(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RolloverRequest request) {
        String sheet = sheetId.trim();
        List<Period> made = rollovers.rollover(sheet, user.trim(), Integer.parseInt(request.year().trim()));
        return views(sheet, made);
    }

    /** Undo the sheet's last rollover; the sheet's periods as they now are. Refused as {@link RolloverService#undo} says. */
    @PostMapping("/rollover/undo")
    public List<PeriodView> rolloverUndo(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        String sheet = sheetId.trim();
        rollovers.undo(sheet, user.trim());
        return views(sheet, service.list(sheet));
    }

    /** A calendar year to generate the quarters of, as yyyy. */
    public record GenerateQuartersRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}\\s*") String year) {
    }

    /** Generate the four calendar quarters of the year as periods yyyy-Q1 to yyyy-Q4; the periods made. */
    @PostMapping("/generate-quarters")
    public List<PeriodView> generateQuarters(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid GenerateQuartersRequest request) {
        String sheet = sheetId.trim();
        List<Period> made = service.generateQuarters(sheet, user.trim(), Integer.parseInt(request.year().trim()));
        return views(sheet, made);
    }

    private List<PeriodView> views(String sheetId, List<Period> periods) {
        Function<LocalDate, String> fiscalYears = service.fiscalYears(sheetId);
        return periods.stream().map(p -> PeriodView.of(p, fiscalYears)).toList();
    }

    private PeriodView view(String sheetId, Period period) {
        return PeriodView.of(period, service.fiscalYears(sheetId));
    }

    /** Pasted lines of opening balances, one name,period,amount per line. */
    public record OpeningImportRequest(@NotNull @Size(max = 1000000) String text) {
    }

    /** How many lines of opening balances were added. */
    public record OpeningImportResult(int count) {
    }

    /** Add an opening balance per pasted line; refused as a whole when any line is. */
    @PostMapping("/openings")
    public OpeningImportResult importOpenings(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid OpeningImportRequest request) {
        return new OpeningImportResult(openings.importOpenings(sheetId.trim(), user.trim(), request.text()));
    }

    /** Close a period, fixing its due-total; refused when it is already closed. */
    @PostMapping("/{name}/close")
    public PeriodView close(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId.trim(), service.close(sheetId.trim(), user.trim(), name.trim()));
    }

    /** Soft-close a period, fixing its figures while its claims can still change; refused when closed or soft-closed. */
    @PostMapping("/{name}/soft-close")
    public PeriodView softClose(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId.trim(), service.softClose(sheetId.trim(), user.trim(), name.trim()));
    }

    /** Reopen a closed period, making its due-total live again; refused for a non-steward or a period not closed. */
    @PostMapping("/{name}/reopen")
    public PeriodView reopen(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId.trim(), service.reopen(sheetId.trim(), user.trim(), name.trim()));
    }

    /** Lock a period so it cannot be reopened; refused for a non-steward or a period already locked. */
    @PostMapping("/{name}/lock")
    public PeriodView lock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId.trim(), service.setLocked(sheetId.trim(), user.trim(), name.trim(), true));
    }

    /** Unlock a locked period; refused for a non-steward or a period not locked. */
    @PostMapping("/{name}/unlock")
    public PeriodView unlock(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 100) String name,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return view(sheetId.trim(), service.setLocked(sheetId.trim(), user.trim(), name.trim(), false));
    }
}
