package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in (a name, no password), loading a sheet by id, and changing a sheet's today. */
@RestController
@RequestMapping("/api")
public class SheetController {

    public record SignInRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record SignInResponse(String name) {
    }

    public record LoadSheetRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user) {
    }

    public record SetTodayRequest(@NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 100) String user,
            @NotNull LocalDate today) {
    }

    public record SheetResponse(String id, LocalDate today) {
    }

    private static SheetResponse response(Sheet sheet) {
        return new SheetResponse(sheet.getId(), sheet.getToday());
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
        return response(service.load(req.id().trim(), req.user().trim()));
    }

    @PostMapping("/sheets/today")
    public SheetResponse setToday(@Valid @RequestBody SetTodayRequest req) {
        return response(service.setToday(req.id().trim(), req.user().trim(), req.today()));
    }
}
