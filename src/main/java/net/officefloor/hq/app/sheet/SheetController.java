package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import net.officefloor.hq.app.access.AccessService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetController {

    /** The sheet: its id, today, whether it is archived, and the id of its parent sheet (null: none). */
    public record SheetView(String id, LocalDate today, boolean archived, String parent) {
    }

    public record TodayChange(@NotNull LocalDate today) {
    }

    private final SheetService service;
    private final AccessService access;

    public SheetController(SheetService service, AccessService access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank String user) {
        return view(service.load(id.trim(), user.trim()));
    }

    /** The sheet as shown: under an as-of view, its today is the as-of date and the rest as it stood then. */
    @GetMapping("/{id}")
    public SheetView get(@PathVariable @NotBlank @Size(max = 100) String id) {
        return view(service.get(id.trim()));
    }

    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody TodayChange change) {
        return view(service.setToday(id.trim(), user.trim(), change.today()));
    }

    /** Archive the sheet: a steward only, and once; anyone else, or again, is refused (403). */
    @PostMapping("/{id}/archive")
    public SheetView archive(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank String user) {
        return view(access.archive(id.trim(), user.trim()));
    }

    private SheetView view(Sheet sheet) {
        return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), service.settings(sheet.getId()).parent());
    }
}
