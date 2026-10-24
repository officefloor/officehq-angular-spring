package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** The void reasons on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
@Validated
public class VoidReasonController {

    /** A void reason: its id and its text. */
    public record VoidReasonView(long id, String reason) {
        static VoidReasonView of(SheetVoidReason r) {
            return new VoidReasonView(r.getId(), r.getReason());
        }
    }

    /** A void reason asked: its text, trimmed, not blank. */
    public record VoidReasonRequest(@NotNull @Size(max = 200) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    @GetMapping
    public List<VoidReasonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.reasons(sheetId.trim()).stream().map(VoidReasonView::of).toList();
    }

    @PostMapping
    public List<VoidReasonView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid VoidReasonRequest request) {
        String sheet = sheetId.trim();
        String reason = request.reason().trim();
        if (reason.isEmpty()) throw new VoidReasonBlankException();
        service.add(sheet, user.trim(), reason);
        return list(sheet);
    }
}
