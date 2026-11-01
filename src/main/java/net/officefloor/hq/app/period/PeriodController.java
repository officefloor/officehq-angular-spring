package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
     * due-total for it fixed then, by person id (two decimals; empty while open).
     */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed,
            String closedDueTotal, Map<Long, String> closedPersonDues) {
        static PeriodView of(Period period) {
            Map<Long, String> personDues = new TreeMap<>();
            period.getClosedPersonDues().forEach((personId, due) -> personDues.put(personId, due.setScale(2).toPlainString()));
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate(),
                    period.isClosed(),
                    period.getClosedDueTotal() == null ? null : period.getClosedDueTotal().setScale(2).toPlainString(),
                    personDues);
        }
    }

    /** A name of letters, digits, '.', '_' or '-', and a from and to date, both inclusive. */
    public record PeriodRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String name,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {}

    /** The first and last dates whose months get a period each; blank takes the sheet's today. */
    public record GenerateRequest(LocalDate from, LocalDate to) {}

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
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
        return PeriodView.of(service.add(sheetId, user, request.name(), request.from(), request.to()));
    }

    /** Make a period for each month from the from-date's to the to-date's: those made, earliest first. */
    @PostMapping("/generate")
    public List<PeriodView> generate(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody GenerateRequest request) {
        return service.generate(sheetId, user, request.from(), request.to()).stream().map(PeriodView::of).toList();
    }

    @PostMapping("/{id}/close")
    public PeriodView close(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId, user, id));
    }

    @PostMapping("/{id}/reopen")
    public PeriodView reopen(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId, user, id));
    }
}
