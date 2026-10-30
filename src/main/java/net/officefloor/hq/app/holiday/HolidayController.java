package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** The holidays on a sheet, earliest first. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/holidays")
@Validated
public class HolidayController {

    public record HolidayView(long id, LocalDate date) {

        static HolidayView of(Holiday holiday) {
            return new HolidayView(holiday.getId(), holiday.getDay());
        }
    }

    public record HolidayForm(@NotNull LocalDate date) {
    }

    public record Refusal(String message) {
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(HolidayView::of).toList();
    }

    @PostMapping
    public HolidayView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody HolidayForm form) {
        return HolidayView.of(service.add(sheetId.trim(), user.trim(), form.date()));
    }

    @ExceptionHandler(HolidayRefused.class)
    public ResponseEntity<Refusal> refused(HolidayRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user added the same date at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
