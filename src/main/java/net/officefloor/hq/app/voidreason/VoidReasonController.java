package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A void reason as the screen shows it. */
    public record VoidReasonView(long id, String reason) {
        static VoidReasonView of(VoidReason r) {
            return new VoidReasonView(r.getId(), r.getReason());
        }
    }

    /** The void reason form: the required text (trimmed). */
    public record VoidReasonForm(@NotBlank @Size(max = 200) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    @GetMapping
    public List<VoidReasonView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return service.list(sheetId.trim()).stream()
                .filter(r -> asOf == null || !r.getAddedOn().isAfter(asOf))
                .map(VoidReasonView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoidReasonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody VoidReasonForm form) {
        return VoidReasonView.of(service.add(sheetId.trim(), user.trim(), form.reason()));
    }
}
