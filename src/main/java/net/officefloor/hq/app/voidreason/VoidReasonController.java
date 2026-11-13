package net.officefloor.hq.app.voidreason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
public class VoidReasonController {

    /** A void reason as the screen shows it. */
    public record VoidReasonView(Long id, String reason) {
        static VoidReasonView of(VoidReason reason) {
            return new VoidReasonView(reason.getId(), reason.getReason());
        }
    }

    /** A void reason: its text, trimmed as it is added. */
    public record VoidReasonRequest(@NotBlank @Size(max = 255) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    /** The sheet's void reasons, in the order they were added. */
    @GetMapping
    public List<VoidReasonView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(VoidReasonView::of).toList();
    }

    /** Add a void reason to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoidReasonView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated VoidReasonRequest request) {
        return VoidReasonView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.reason()));
    }

    /** A blank reason, or one already on the sheet's list, is refused. */
    @ExceptionHandler({VoidReasonRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
