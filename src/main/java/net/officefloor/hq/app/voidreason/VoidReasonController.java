package net.officefloor.hq.app.voidreason;

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

/** The void reasons on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
public class VoidReasonController {

    /** A void reason as the screen shows it. */
    public record VoidReasonView(long id, String reason) {

        public static VoidReasonView of(VoidReason r) {
            return new VoidReasonView(r.getId(), r.getReason());
        }
    }

    /** A void reason as the form submits it. */
    public record VoidReasonForm(@NotNull @Size(max = 200) String reason) {
    }

    private final VoidReasonService reasons;

    public VoidReasonController(VoidReasonService reasons) {
        this.reasons = reasons;
    }

    @GetMapping
    public List<VoidReasonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return reasons.list(sheetId).stream().map(VoidReasonView::of).toList();
    }

    @PostMapping
    public VoidReasonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody VoidReasonForm request) {
        return VoidReasonView.of(reasons.add(sheetId, user, request.reason()));
    }
}
