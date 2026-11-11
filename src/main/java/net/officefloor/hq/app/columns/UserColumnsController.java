package net.officefloor.hq.app.columns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people-list columns the signed-in user has switched off. The user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/me/columns")
@Validated
public class UserColumnsController {

    /** The switched-off column keys. */
    public record ColumnsView(List<String> hidden) {
    }

    /** The column keys to switch off; every other column shows. */
    public record ColumnsRequest(@NotNull @Size(max = 50) List<@NotNull @Size(max = 50) String> hidden) {
    }

    private final UserColumnsService service;

    public UserColumnsController(UserColumnsService service) {
        this.service = service;
    }

    @GetMapping
    public ColumnsView get(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new ColumnsView(service.hiddenOf(user.trim()));
    }

    @PutMapping
    public ColumnsView choose(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ColumnsRequest request) {
        return new ColumnsView(service.choose(user.trim(), request.hidden()));
    }
}
