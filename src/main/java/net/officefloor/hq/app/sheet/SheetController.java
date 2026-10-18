package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    public record SheetView(String id, LocalDate today) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday());
        }
    }

    public record TodayChange(@NotNull LocalDate today) {}

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty when it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String id,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return SheetView.of(service.load(id, user));
    }

    /** Change the sheet's "today". */
    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String id,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody TodayChange change) {
        return SheetView.of(service.setToday(id, user, change.today()));
    }
}
