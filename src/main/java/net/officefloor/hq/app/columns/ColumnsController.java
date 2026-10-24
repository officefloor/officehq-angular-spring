package net.officefloor.hq.app.columns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people-list columns the signed-in user has switched off on a sheet. The signed-in user's name
 * travels in the {@code X-User} header. Choosing them is not a change to the sheet: it goes through on an
 * archived sheet, as of a date and for a viewer (see the guards' exclusions). */
@RestController
@RequestMapping("/api/sheets/{sheetId}/columns")
@Validated
public class ColumnsController {

    /** The columns a user has switched off. */
    public record ColumnsView(List<String> hidden) {
    }

    /** The columns asked to be switched off. */
    public record ColumnsRequest(@NotNull @Size(max = 50) List<@NotNull @Size(max = 50) String> hidden) {
    }

    private final ColumnsService service;

    public ColumnsController(ColumnsService service) {
        this.service = service;
    }

    @GetMapping
    public ColumnsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new ColumnsView(service.hiddenOf(sheetId.trim(), user.trim()));
    }

    @PutMapping
    public ColumnsView choose(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ColumnsRequest request) {
        return new ColumnsView(service.choose(sheetId.trim(), user.trim(), request.hidden()));
    }
}
