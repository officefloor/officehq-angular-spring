package net.officefloor.hq.app.grade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's grades, and those retired on it. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/grades")
@Validated
public class GradeController {

    /** A grade to retire: one of the sheet's grades. */
    public record RetireRequest(@NotNull @Pattern(regexp = "[A-Z]{1,3}") String grade) {
    }

    /** A grade to add: its code, one to three capital letters, trimmed. */
    public record AddRequest(@NotNull String grade) {
    }

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    @GetMapping
    public List<String> grades(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.grades(sheetId.trim());
    }

    @PostMapping
    public List<String> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid AddRequest request) {
        String sheet = sheetId.trim();
        service.add(sheet, user.trim(), request.grade().trim());
        return grades(sheet);
    }

    @GetMapping("/retired")
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.retired(sheetId.trim());
    }

    @PostMapping("/retired")
    public List<String> retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RetireRequest request) {
        String sheet = sheetId.trim();
        service.retire(sheet, user.trim(), request.grade());
        return list(sheet);
    }
}
