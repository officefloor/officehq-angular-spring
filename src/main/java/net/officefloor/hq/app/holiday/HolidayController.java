package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.server.ResponseStatusException;

/** The holidays of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/holidays")
@Validated
public class HolidayController {

    /** A holiday as shown, its date as yyyy-mm-dd. */
    public record HolidayView(Long id, String date) {

        static HolidayView of(Holiday h) {
            return new HolidayView(h.getId(), h.getDate().toString());
        }
    }

    /** The holiday's date as yyyy-mm-dd. */
    public record HolidayRequest(@NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String date) {

        LocalDate parsed() {
            try {
                return LocalDate.parse(date.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + date.trim());
            }
        }
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    /** The sheet's holidays, earliest first. */
    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(HolidayView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid HolidayRequest request) {
        return HolidayView.of(service.add(sheetId.trim(), user.trim(), request.parsed()));
    }
}
