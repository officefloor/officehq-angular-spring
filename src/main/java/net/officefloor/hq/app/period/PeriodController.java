package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.session.CurrentUser;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/periods")
@Validated
public class PeriodController {

    /** A period as the screen shows it: whether it is closed, the due-total fixed when it was
     *  closed (null while open), and each person's due-total for it fixed then, by person id
     *  (empty while open). */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to, boolean closed, String closedTotal,
            Map<Long, String> closedPersonTotals) {
        static PeriodView of(Period p) {
            Map<Long, String> personTotals = new LinkedHashMap<>();
            p.getClosedPersonTotals().forEach((id, total) -> personTotals.put(id, total.setScale(2).toPlainString()));
            return new PeriodView(p.getId(), p.getName(), p.getFrom(), p.getTo(), p.isClosed(),
                    p.getClosedTotal() == null ? null : p.getClosedTotal().setScale(2).toPlainString(), personTotals);
        }
    }

    /** A period as the form submits it: a name without spaces, an optional from-date (the sheet's
     *  today when absent) and a to-date. */
    public record PeriodForm(
            @NotBlank @Size(max = 100) @Pattern(regexp = "\\s*\\S+\\s*") String name,
            LocalDate from,
            @NotNull LocalDate to) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    @GetMapping
    public List<PeriodView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(PeriodView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodForm body) {
        return PeriodView.of(service.add(sheetId, user.trim(), body.name(), body.from(), body.to()));
    }

    /** A month range as the generate form submits it: an optional from-date (the sheet's today
     *  when absent) and a to-date. */
    public record GenerateForm(LocalDate from, @NotNull LocalDate to) {
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PeriodView> generate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GenerateForm body) {
        return service.generate(sheetId, user.trim(), body.from(), body.to()).stream().map(PeriodView::of).toList();
    }

    @PostMapping("/{id}/close")
    public PeriodView close(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.close(sheetId, user.trim(), id));
    }

    @PostMapping("/{id}/reopen")
    public PeriodView reopen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return PeriodView.of(service.reopen(sheetId, user.trim(), id));
    }
}
