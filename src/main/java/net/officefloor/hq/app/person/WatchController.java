package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** The people the signed-in user (the {@code X-User} header) watches on a sheet, by id. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class WatchController {

    /** Start (true) or stop (false) watching. */
    public record WatchForm(@NotNull Boolean on) {
    }

    private final WatchService watches;

    public WatchController(WatchService watches) {
        this.watches = watches;
    }

    @GetMapping("/watches")
    public List<Long> watched(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user) {
        return watches.watched(sheetId.trim(), user.trim());
    }

    /** Start or stop watching the person; answers with the ids of everyone the user watches now. */
    @PutMapping("/people/{personId}/watch")
    public List<Long> watch(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody WatchForm form) {
        return watches.watch(sheetId.trim(), user.trim(), personId, form.on());
    }
}
