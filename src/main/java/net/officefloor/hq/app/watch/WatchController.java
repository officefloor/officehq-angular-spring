package net.officefloor.hq.app.watch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people the signed-in user watches on a sheet. The caller's signed-in name comes in the {@code X-User}
 *  header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class WatchController {

    /** Whether the caller watches a person. */
    public record WatchView(long personId, boolean watched) {
    }

    /** Whether the caller is to watch the person. */
    public record WatchForm(@NotNull Boolean watched) {
    }

    private final WatchService watches;

    public WatchController(WatchService watches) {
        this.watches = watches;
    }

    /** The ids of the people the caller watches. */
    @GetMapping("/watches")
    public List<Long> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return watches.list(sheetId, user).stream().map(Watch::getPersonId).toList();
    }

    @PutMapping("/people/{id}/watch")
    public WatchView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody WatchForm request) {
        return new WatchView(id, watches.set(sheetId, user, id, request.watched()));
    }
}
