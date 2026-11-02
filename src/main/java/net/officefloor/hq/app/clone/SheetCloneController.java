package net.officefloor.hq.app.clone;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets")
@Validated
public class SheetCloneController {

    /** The id of the sheet to clone to. */
    public record CloneRequest(@NotBlank @Size(max = 100) String to) {
    }

    /** The id the sheet was cloned to. */
    public record CloneView(String to) {
    }

    private final SheetCloneService service;

    public SheetCloneController(SheetCloneService service) {
        this.service = service;
    }

    /** Clone the sheet to another id, never used before; only a steward may. */
    @PostMapping("/{id}/clone")
    public CloneView cloneSheet(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CloneRequest request) {
        String to = request.to().trim();
        service.cloneSheet(id.trim(), user.trim(), to);
        return new CloneView(to);
    }
}
