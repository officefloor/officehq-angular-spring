package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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

/** The sheet's periods. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/periods")
public class PeriodController {

    /** A period as the screen shows it: its name and its from and to dates (YYYY-MM-DD). */
    public record PeriodView(long id, String name, String from, String to) {

        static PeriodView of(Period p) {
            return new PeriodView(p.getId(), p.getName(), p.getFromDate().toString(), p.getToDate().toString());
        }
    }

    public record PeriodRequest(@NotBlank @Size(max = 200) String name, @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    /** The sheet's periods, by from-date. */
    @GetMapping
    public List<PeriodView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(PeriodView::of).toList();
    }

    /** Add a period; refused when its name is taken, its dates run backwards or it overlaps another. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PeriodRequest request) {
        return PeriodView.of(service.add(sheetId.trim(), user.trim(), request.name(), request.from(), request.to()));
    }
}
