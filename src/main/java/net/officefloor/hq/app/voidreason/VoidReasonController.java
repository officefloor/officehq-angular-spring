package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
@Validated
public class VoidReasonController {

    /** What the screen is told about a void reason: its text. */
    public record VoidReasonView(long id, String reason) {

        static VoidReasonView of(VoidReason reason) {
            return new VoidReasonView(reason.getId(), reason.getReason());
        }
    }

    /** A void reason as entered on the form: text, not blank. */
    public record VoidReasonForm(@NotBlank @Size(max = 100) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    /** The sheet's void reasons in the order they were added. */
    @GetMapping
    public List<VoidReasonView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(VoidReasonView::of).toList();
    }

    /** Add a void reason to the sheet. */
    @PostMapping
    public VoidReasonView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid VoidReasonForm form) {
        return VoidReasonView.of(service.add(sheetId, SignedIn.name(user), form.reason()));
    }

    /** Another user added the reason between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void reasonTaken() {
    }
}
