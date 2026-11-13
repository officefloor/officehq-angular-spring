package net.officefloor.hq.app.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Which columns of the people list the signed-in user's screen shows. The user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/user/columns")
public class UserColumnsController {

    public record ColumnsView(List<String> hidden) {
    }

    public record ShowRequest(@NotNull Boolean shown) {
    }

    private final UserColumnsService service;

    public UserColumnsController(UserColumnsService service) {
        this.service = service;
    }

    /** The columns the user switched off: none unless they did. */
    @GetMapping
    public ColumnsView get(@RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return new ColumnsView(service.hidden(user.trim()));
    }

    /** Switch one column on or off for the user. */
    @PutMapping("/{column}")
    public ColumnsView show(@RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @PathVariable @Size(max = 40) String column, @RequestBody @Validated ShowRequest request) {
        return new ColumnsView(service.show(user.trim(), column, request.shown()));
    }

    /** A column that cannot be switched is refused with the reason, for the screen to show. */
    @ExceptionHandler(ColumnRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(ColumnRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
