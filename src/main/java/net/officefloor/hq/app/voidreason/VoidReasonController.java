package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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

/** A sheet's list of void reasons. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/void-reasons")
public class VoidReasonController {

    public record VoidReasonView(long id, String reason) {

        static VoidReasonView of(VoidReason r) {
            return new VoidReasonView(r.getId(), r.getReason());
        }
    }

    public record VoidReasonRequest(@Size(max = 200) String reason) {
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    /** The sheet's void reasons, in the order they were added. */
    @GetMapping
    public List<VoidReasonView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(VoidReasonView::of).toList();
    }

    /** Add a reason to the sheet's void reasons. */
    @PostMapping
    public VoidReasonView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid VoidReasonRequest request) {
        return VoidReasonView.of(service.add(sheetId.trim(), user.trim(), request.reason()));
    }

    /** A void reason that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(VoidReasonRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(VoidReasonRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
