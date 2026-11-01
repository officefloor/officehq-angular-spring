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

@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    public record SheetView(String id) {}

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load (or start) a sheet by id for the signed-in user. */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new SheetView(service.load(id, user.trim()).getId());
    }
}
