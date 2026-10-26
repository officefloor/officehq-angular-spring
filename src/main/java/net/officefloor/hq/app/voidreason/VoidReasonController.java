package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
public class VoidReasonController {

    public record VoidReasonView(Long id, String reason) {
        static VoidReasonView of(VoidReason r) {
            return new VoidReasonView(r.getId(), r.getReason());
        }
    }

    public record VoidReasonForm(@NotNull @Size(max = 140) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    /** The sheet's void reasons, in the order they were added. */
    @GetMapping
    public List<VoidReasonView> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(VoidReasonView::of).toList();
    }

    /** Add a reason; a blank one, or one already on the list, is refused. */
    @PostMapping
    public VoidReasonView add(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody VoidReasonForm form, @SessionAttribute(SessionController.USER) String user) {
        return VoidReasonView.of(service.add(user, sheetId, form.reason()));
    }
}
