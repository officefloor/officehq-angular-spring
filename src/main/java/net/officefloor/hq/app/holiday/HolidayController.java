package net.officefloor.hq.app.holiday;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/holidays")
public class HolidayController {

    /** A holiday as the screen shows it. */
    public record HolidayView(Long id, LocalDate date) {
        static HolidayView of(Holiday holiday) {
            return new HolidayView(holiday.getId(), holiday.getDate());
        }
    }

    /** A holiday: its date. */
    public record HolidayRequest(@NotNull LocalDate date) {
    }

    private final HolidayService service;
    private final RollupService rollups;

    public HolidayController(HolidayService service, RollupService rollups) {
        this.service = service;
        this.rollups = rollups;
    }

    /** The sheet's holidays, the earliest first. */
    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(HolidayView::of).toList();
    }

    /** Add a holiday to the sheet, then record the figures the due-bys it re-fixes moved. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated HolidayRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return HolidayView.of(rollups.track(sheet, by, () -> service.add(sheet, by, request.date())));
    }

    /** A date already on the sheet's list is refused. */
    @ExceptionHandler({HolidayRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** A date that is not a real yyyy-mm-dd date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
