package net.officefloor.hq.app.holiday;

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

/** The holidays on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/holidays")
@Validated
public class HolidayController {

    /** A holiday: its id and its date. */
    public record HolidayView(long id, LocalDate date) {
        static HolidayView of(SheetHoliday h) {
            return new HolidayView(h.getId(), h.getDate());
        }
    }

    /** A holiday asked: its date (YYYY-MM-DD). */
    public record HolidayRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String date) {
        LocalDate dateValue() {
            return LocalDate.parse(date.trim());
        }
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.holidays(sheetId.trim()).stream().map(HolidayView::of).toList();
    }

    @PostMapping
    public List<HolidayView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid HolidayRequest request) {
        String sheet = sheetId.trim();
        service.add(sheet, user.trim(), request.dateValue());
        return list(sheet);
    }
}
