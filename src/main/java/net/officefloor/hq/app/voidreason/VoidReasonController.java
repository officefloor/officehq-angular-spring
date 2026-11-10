package net.officefloor.hq.app.voidreason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and adding the void reasons on a sheet. */
@RestController
@RequestMapping("/api/void-reasons")
public class VoidReasonController {

    public record ListVoidReasonsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddVoidReasonRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 200) String reason) {
    }

    public record VoidReasonResponse(long id, String reason) {
    }

    private static VoidReasonResponse response(VoidReason reason) {
        return new VoidReasonResponse(reason.getId(), reason.getReason());
    }

    private final VoidReasonService service;

    public VoidReasonController(VoidReasonService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<VoidReasonResponse> list(@Valid @RequestBody ListVoidReasonsRequest req) {
        return service.list(req.sheet().trim()).stream().map(VoidReasonController::response).toList();
    }

    @PostMapping("/add")
    public VoidReasonResponse add(@Valid @RequestBody AddVoidReasonRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.reason()));
    }
}
