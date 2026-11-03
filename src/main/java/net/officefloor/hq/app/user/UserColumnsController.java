package net.officefloor.hq.app.user;

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

/** The people-list columns the signed-in user's screen leaves out, kept per user across every sheet. */
@RestController
@RequestMapping("/api/me/columns")
@Validated
public class UserColumnsController {

    public record ColumnsChange(@NotNull @Size(max = 50) List<@NotBlank String> hidden) {
    }

    public record ColumnsView(List<String> hidden) {
    }

    private final UserPrefService service;

    public UserColumnsController(UserPrefService service) {
        this.service = service;
    }

    @GetMapping
    public ColumnsView get(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return new ColumnsView(service.hiddenColumnsOf(user.trim()));
    }

    @PutMapping
    public ColumnsView set(@RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ColumnsChange request) {
        return new ColumnsView(service.setHiddenColumns(user.trim(), request.hidden()));
    }
}
