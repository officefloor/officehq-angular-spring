package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
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

    /** The id of the sheet to clone this one to. */
    public record CloneRequest(@NotBlank @Size(max = 100) String to) {
    }

    private final SheetService service;
    private final AccessService access;
    private final SheetCloneService clones;

    public SheetController(SheetService service, AccessService access, SheetCloneService clones) {
        this.service = service;
        this.access = access;
        this.clones = clones;
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

    /** The ids of the sheet's descendants: its children, their children, and so on, nearest first. */
    @GetMapping("/{id}/descendants")
    public List<String> descendants(@PathVariable @NotBlank @Size(max = 100) String id) {
        return service.descendants(id.trim());
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

    /**
     * Clone the sheet to another id: a steward only, and only to an id never used; anyone else (403), or a
     * used id (409), is refused.
     */
    @PostMapping("/{id}/clone")
    public SheetView cloneTo(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody CloneRequest request) {
        return view(clones.cloneTo(id.trim(), user.trim(), request.to().trim()));
    }

    private SheetView view(Sheet sheet) {
        return new SheetView(sheet.getId(), sheet.getToday(), sheet.isArchived(), service.settings(sheet.getId()).parent());
    }
}
