package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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

    /** A period as the screen shows it. */
    public record PeriodView(long id, String name, LocalDate from, LocalDate to) {
        static PeriodView of(Period period) {
            return new PeriodView(period.getId(), period.getName(), period.getFromDate(), period.getToDate());
        }
    }

    /** A name of letters, digits, '.', '_' or '-', and a from and to date, both inclusive. */
    public record PeriodRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String name,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {}

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
}
