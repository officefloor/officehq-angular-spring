package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /**
     * A period as the screen shows it; a closed or soft-closed one carries the due-total fixed when it was closed, and each person's
     * due-total and carried figure then, by person id; and each person's opening balance for it, by person id.
     */
    public record PeriodView(Long id, String name, LocalDate from, LocalDate to, Integer cap, boolean closed,
            boolean softClosed, String closedTotal, Map<Long, String> closedPersonTotals,
            Map<Long, String> closedPersonCarried, Map<Long, String> personOpenings) {
        static PeriodView of(Period p) {
            Map<Long, String> personTotals = new TreeMap<>();
            p.getClosedPersonTotals().forEach((person, total) -> personTotals.put(person, total.toPlainString()));
            Map<Long, String> personCarried = new TreeMap<>();
            p.getClosedPersonCarried().forEach((person, carried) -> personCarried.put(person, carried.toPlainString()));
            Map<Long, String> openings = new TreeMap<>();
            p.getPersonOpenings().forEach((person, amount) -> openings.put(person, amount.toPlainString()));
            return new PeriodView(p.getId(), p.getName(), p.getFrom(), p.getTo(), p.getCap(), p.isClosed(), p.isSoftClosed(),
                    p.getClosedTotal() == null ? null : p.getClosedTotal().toPlainString(), personTotals, personCarried,
                    openings);
        }
    }

    /**
     * A period as the form submits it: a name, its from and to dates, inclusive, and the most OPEN or PENDING claims a
     * person may have in it (null for no cap).
     */
    public record PeriodForm(@NotBlank @Size(max = 100) String name,
            @NotNull LocalDate from, @NotNull LocalDate to, @Min(1) @Max(1000000) Integer cap) {
    }

    /** The months to generate periods for: those of the from-date through the to-date. */
    public record GenerateForm(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    /** The fiscal year to generate periods for: FY<year>. */
    public record GenerateFiscalYearForm(@NotNull @Min(1) @Max(9998) Integer year) {
    }

    /** The opening balances paste box: lines of name,period,amount. */
    public record OpeningsForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** How many pasted lines of opening balances went through. */
    public record OpeningsDone(int count) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods, by from-date. */
    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    /** Adds each pasted line's amount to the person's opening balance for the period, all lines or none. */
    @PostMapping("/openings")
    public OpeningsDone importOpenings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody OpeningsForm form, @SessionAttribute(SessionController.USER) String user) {
        return new OpeningsDone(service.importOpenings(user, sheetId, form.text()));
    }

    @PostMapping
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PeriodForm form, @SessionAttribute(SessionController.USER) String user) {
        return PeriodView.of(service.add(user, sheetId, form.name(), form.from(), form.to(), form.cap()));
    }

    /** Generates monthly periods; answers the ones made, by month. */
    @PostMapping("/generate")
    public List<PeriodView> generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody GenerateForm form, @SessionAttribute(SessionController.USER) String user) {
        return service.generate(user, sheetId, form.from(), form.to()).stream().map(PeriodView::of).toList();
    }

    /** Generates the fiscal year's monthly periods; answers the ones made, by month. */
    @PostMapping("/generate-fy")
    public List<PeriodView> generateFiscalYear(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody GenerateFiscalYearForm form, @SessionAttribute(SessionController.USER) String user) {
        return service.generateFiscalYear(user, sheetId, form.year()).stream().map(PeriodView::of).toList();
    }

    @PostMapping("/{id}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return PeriodView.of(service.close(user, sheetId, id));
    }

    @PostMapping("/{id}/soft-close")
    public PeriodView softClose(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return PeriodView.of(service.softClose(user, sheetId, id));
    }

    @PostMapping("/{id}/reopen")
    public PeriodView reopen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @SessionAttribute(SessionController.USER) String user) {
        return PeriodView.of(service.reopen(user, sheetId, id));
    }
}
