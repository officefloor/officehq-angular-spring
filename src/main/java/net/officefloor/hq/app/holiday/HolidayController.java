package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/sheets/{sheetId}/holidays")
@Validated
public class HolidayController {

    /** A holiday as the screen shows it: its day. */
    public record HolidayView(long id, LocalDate date) {
        static HolidayView of(Holiday h) {
            return new HolidayView(h.getId(), h.getDay());
        }
    }

    /** The holiday form: the required day. */
    public record HolidayForm(@NotNull LocalDate date) {
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return service.list(sheetId.trim()).stream()
                .filter(h -> asOf == null || !h.getAddedOn().isAfter(asOf))
                .map(HolidayView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody HolidayForm form) {
        return HolidayView.of(service.add(sheetId.trim(), user.trim(), form.date()));
    }
}
