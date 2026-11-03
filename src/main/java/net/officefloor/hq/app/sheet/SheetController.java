package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in (a name, no password) and loading a sheet by id. */
@RestController
@RequestMapping("/api")
public class SheetController {

    public record SignInRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record SignInResponse(String name) {
    }

    public record LoadSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user) {
    }

    public record SheetResponse(String id) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    @PostMapping("/signin")
    public SignInResponse signIn(@Valid @RequestBody SignInRequest req) {
        return new SignInResponse(req.name().trim());
    }

    @PostMapping("/sheets/load")
    public SheetResponse load(@Valid @RequestBody LoadSheetRequest req) {
        return new SheetResponse(service.load(req.id().trim(), req.user().trim()).getId());
    }
}
