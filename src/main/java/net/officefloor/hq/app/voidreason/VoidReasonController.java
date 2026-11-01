package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
public class VoidReasonController {

    /** A void reason on the sheet. */
    public record VoidReasonView(Long id, String reason) {
        static VoidReasonView of(VoidReason r) {
            return new VoidReasonView(r.getId(), r.getReason());
        }
    }

    /** A void reason's text. */
    public record VoidReasonRequest(@NotBlank @Size(max = 200) String reason) {}

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    @GetMapping
    public List<VoidReasonView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.reasons(sheetId).stream().map(VoidReasonView::of).toList();
    }

    @PostMapping
    public VoidReasonView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid VoidReasonRequest request) {
        return VoidReasonView.of(service.add(sheetId, user, request.reason()));
    }
}
