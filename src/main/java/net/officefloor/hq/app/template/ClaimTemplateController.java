package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and saving the claim templates on a sheet, and adding a claim from one. */
@RestController
@RequestMapping("/api/templates")
public class ClaimTemplateController {

    public record ListTemplatesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    /** A template's amount is one the claim form takes without a correction: 0.00 or more, up to two decimals. */
    public record SaveTemplateRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 200) String name, @NotNull @Pattern(regexp = ClaimController.AMOUNT) String amount,
            @Size(max = 1000) String note) {
    }

    public record ClaimFromTemplateRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long personId, @NotBlank @Size(max = 200) String name) {
    }

    public record TemplateResponse(long id, String name, String amount, String note) {
    }

    private static TemplateResponse response(ClaimTemplate template) {
        return new TemplateResponse(template.getId(), template.getName(),
                template.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), template.getNote());
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<TemplateResponse> list(@Valid @RequestBody ListTemplatesRequest req) {
        return service.list(req.sheet().trim()).stream().map(ClaimTemplateController::response).toList();
    }

    @PostMapping("/save")
    public TemplateResponse save(@Valid @RequestBody SaveTemplateRequest req) {
        return response(service.save(req.sheet().trim(), req.user().trim(), req.name(),
                ClaimController.amount(req.amount()), req.note()));
    }

    @PostMapping("/claim")
    public ClaimResponse claim(@Valid @RequestBody ClaimFromTemplateRequest req) {
        return ClaimController.response(service.addClaim(req.sheet().trim(), req.user().trim(), req.personId(),
                req.name()));
    }
}
