package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    public record SheetView(String id) {
    }

    private final SheetService sheets;

    public SheetController(SheetService sheets) {
        this.sheets = sheets;
    }

    /** Load (re-read) a sheet by id, starting it empty if it has never been loaded. */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new SheetView(sheets.load(id, user).getId());
    }
}
