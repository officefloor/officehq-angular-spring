package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's list of holidays. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/holidays")
public class HolidayController {

    public record HolidayView(long id, String date) {

        static HolidayView of(Holiday h) {
            return new HolidayView(h.getId(), h.getDate().toString());
        }
    }

    public record HolidayRequest(@NotNull LocalDate date) {
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    /** The sheet's holidays, earliest first. */
    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(HolidayView::of).toList();
    }

    /** Add a date to the sheet's holidays. */
    @PostMapping
    public HolidayView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid HolidayRequest request) {
        return HolidayView.of(service.add(sheetId.trim(), user.trim(), request.date()));
    }

    /** A holiday that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(HolidayRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(HolidayRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
