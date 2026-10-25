package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
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

    /** What the screen shows of a holiday: its date. */
    public record HolidayView(long id, LocalDate date) {
        static HolidayView of(Holiday holiday) {
            return new HolidayView(holiday.getId(), holiday.getDate());
        }
    }

    /** A holiday's date, as entered on the form. */
    public record HolidayForm(@NotNull LocalDate date) {
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    /** The sheet's holidays in date order. */
    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(HolidayView::of).toList();
    }

    /** Add a holiday to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid HolidayForm form) {
        return HolidayView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.date()));
    }
}
