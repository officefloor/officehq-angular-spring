package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and adding the periods on a sheet. */
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

    public record PeriodResponse(long id, String name, LocalDate from, LocalDate to) {
    }

    private static PeriodResponse response(Period period) {
        return new PeriodResponse(period.getId(), period.getName(), period.getFromDate(), period.getToDate());
    }

    private final PeriodService service;

    public PeriodController(PeriodService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<PeriodResponse> list(@Valid @RequestBody ListPeriodsRequest req) {
        return service.list(req.sheet().trim()).stream().map(PeriodController::response).toList();
    }

    @PostMapping("/add")
    public PeriodResponse add(@Valid @RequestBody AddPeriodRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.name().trim(), req.from(), req.to()));
    }
}
