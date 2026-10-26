package net.officefloor.hq.app.holiday;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/holidays")
public class HolidayController {

    public record HolidayView(Long id, LocalDate date) {
        static HolidayView of(Holiday h) {
            return new HolidayView(h.getId(), h.getDate());
        }
    }

    public record HolidayForm(@NotNull LocalDate date) {
    }

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    /** The sheet's holidays, earliest first. */
    @GetMapping
    public List<HolidayView> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(HolidayView::of).toList();
    }

    /** Add a holiday; a date already on the list is refused. */
    @PostMapping
    public HolidayView add(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId, @Valid @RequestBody HolidayForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return HolidayView.of(service.add(user, sheetId, form.date()));
    }
}
