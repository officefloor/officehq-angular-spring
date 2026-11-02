package net.officefloor.hq.app.watch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class WatchController {

    /** A person the signed-in user watches, by id. */
    public record WatchView(long id, long personId) {
        static WatchView of(Watch watch) {
            return new WatchView(watch.getId(), watch.getPersonId());
        }
    }

    private final WatchService service;

    public WatchController(WatchService service) {
        this.service = service;
    }

    @GetMapping("/api/sheets/{sheetId}/watches")
    public List<WatchView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return service.list(sheetId.trim(), user.trim()).stream()
                .filter(w -> asOf == null || w.getStartedOn() == null || !w.getStartedOn().isAfter(asOf))
                .map(WatchView::of).toList();
    }

    @PostMapping("/api/sheets/{sheetId}/people/{personId}/watch")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchView watch(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return WatchView.of(service.watch(sheetId.trim(), user.trim(), personId));
    }

    @DeleteMapping("/api/sheets/{sheetId}/people/{personId}/watch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unwatch(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        service.unwatch(sheetId.trim(), user.trim(), personId);
    }
}
