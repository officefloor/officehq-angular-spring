package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    public record SheetView(String id) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return new SheetView(service.load(id.trim(), user.trim()).getId());
    }
}
