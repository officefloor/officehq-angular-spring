package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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

    /** What the screen shows of a void reason: its text. */
    public record VoidReasonView(long id, String reason) {
        static VoidReasonView of(VoidReason reason) {
            return new VoidReasonView(reason.getId(), reason.getReason());
        }
    }

    /** A void reason, as typed on the form. */
    public record VoidReasonForm(@NotBlank @Size(max = 100) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    /** The sheet's void reasons in the order they were added. */
    @GetMapping
    public List<VoidReasonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(VoidReasonView::of).toList();
    }

    /** Add a reason to the sheet's list of void reasons. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoidReasonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid VoidReasonForm form) {
        return VoidReasonView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.reason()));
    }
}
