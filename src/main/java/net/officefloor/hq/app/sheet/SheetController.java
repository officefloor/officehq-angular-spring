package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetController {

    public record SheetView(String id) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty if it has never been loaded. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Sheet sheet = service.load(id.trim(), user.trim());
        return new SheetView(sheet.getId());
    }
}
