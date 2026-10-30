package net.officefloor.hq.app.claim;

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

/** The void reasons on a sheet, in the order added. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
@Validated
public class VoidReasonController {

    /** A reason to add; blank is refused. */
    public record VoidReasonForm(@Size(max = 1000) String reason) {
    }

    public record Refusal(String message) {
    }

    private final VoidReasons reasons;

    public VoidReasonController(VoidReasons reasons) {
        this.reasons = reasons;
    }

    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return reasons.list(sheetId.trim());
    }

    /** Add a reason; answers with every reason on the sheet's list. */
    @PostMapping
    public List<String> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody VoidReasonForm form) {
        String sheet = sheetId.trim();
        reasons.add(sheet, user.trim(), form.reason());
        return reasons.list(sheet);
    }

    @ExceptionHandler(VoidReasonRefused.class)
    public ResponseEntity<Refusal> refused(VoidReasonRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user added the same reason at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
