package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The crews on a sheet and their members. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/crews")
@Validated
public class CrewController {

    /** A crew to add: a name (blank is refused) and its lead, a person of the sheet. */
    public record CrewForm(@Size(max = 1000) String name, Long leadId) {
    }

    /** A person of the sheet to add to the named crew. */
    public record MemberForm(@NotBlank @Size(max = 1000) String crew, Long personId) {
    }

    public record Refusal(String message) {
    }

    private final Crews crews;

    public CrewController(Crews crews) {
        this.crews = crews;
    }

    @GetMapping
    public List<Crews.Shown> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return crews.list(sheetId.trim());
    }

    /** Add a crew; answers with every crew on the sheet. */
    @PostMapping
    public List<Crews.Shown> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody CrewForm form) {
        String sheet = sheetId.trim();
        crews.add(sheet, user.trim(), form.name(), form.leadId());
        return list(sheet);
    }

    /** Add a member to a crew; answers with every crew on the sheet. */
    @PostMapping("/members")
    public List<Crews.Shown> addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody MemberForm form) {
        String sheet = sheetId.trim();
        crews.addMember(sheet, user.trim(), form.crew(), form.personId());
        return list(sheet);
    }

    @ExceptionHandler(CrewRefused.class)
    public ResponseEntity<Refusal> refused(CrewRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user added the same crew, or the same member, at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
