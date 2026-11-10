package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and adding the holidays on a sheet. */
@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    public record ListHolidaysRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddHolidayRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull LocalDate date) {
    }

    public record HolidayResponse(long id, LocalDate date) {
    }

    private static HolidayResponse response(Holiday holiday) {
        return new HolidayResponse(holiday.getId(), holiday.getDay());
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<HolidayResponse> list(@Valid @RequestBody ListHolidaysRequest req) {
        return service.list(req.sheet().trim()).stream().map(HolidayController::response).toList();
    }

    @PostMapping("/add")
    public HolidayResponse add(@Valid @RequestBody AddHolidayRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.date()));
    }
}
