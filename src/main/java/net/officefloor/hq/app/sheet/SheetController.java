package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
public class SheetController {

    public record LoadSheet(@NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id) {}

    public record SheetView(String id) {}

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty when it was never loaded before. */
    @PostMapping("/load")
    public SheetView load(@RequestHeader("X-User") String user, @Valid @RequestBody LoadSheet request) {
        return new SheetView(service.load(request.id(), user).getId());
    }
}
