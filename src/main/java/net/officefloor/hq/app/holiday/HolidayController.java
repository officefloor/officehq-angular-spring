package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/sheets/{sheetId}/holidays")
public class HolidayController {

    /** A holiday on the sheet. */
    public record HolidayView(Long id, LocalDate date) {
        static HolidayView of(Holiday h) {
            return new HolidayView(h.getId(), h.getDate());
        }
    }

    /** A holiday's date (yyyy-mm-dd). */
    public record HolidayRequest(@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String date) {

        LocalDate dateValue() {
            return LocalDate.parse(date);
        }
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @GetMapping
    public List<HolidayView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.holidays(sheetId).stream().map(HolidayView::of).toList();
    }

    @PostMapping
    public HolidayView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid HolidayRequest request) {
        return HolidayView.of(service.add(sheetId, user, request.dateValue()));
    }
}
