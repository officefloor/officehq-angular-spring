package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The holidays on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/holidays")
public class HolidayController {

    /** A holiday as the screen shows it. */
    public record HolidayView(long id, LocalDate date) {

        public static HolidayView of(Holiday h) {
            return new HolidayView(h.getId(), h.getDate());
        }
    }

    /** A holiday as the form submits it. */
    public record HolidayForm(@NotNull LocalDate date) {
    }

    private final HolidayService holidays;
    private final SheetHistory history;

    public HolidayController(HolidayService holidays, SheetHistory history) {
        this.holidays = holidays;
        this.history = history;
    }

    @GetMapping
    public List<HolidayView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            List<HolidayView> then = history.viewsAsOf(sheetId, asOf).holidays();
            return then != null ? then : List.of();
        }
        return holidays.list(sheetId).stream().map(HolidayView::of).toList();
    }

    @PostMapping
    public HolidayView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody HolidayForm request) {
        return HolidayView.of(holidays.add(sheetId, user, request.date()));
    }
}
