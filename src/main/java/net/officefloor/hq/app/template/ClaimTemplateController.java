package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimTemplateController {

    /** A claim template on the sheet; its amount has two decimals. */
    public record TemplateView(Long id, String name, String amount, String note) {
        static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getAmount().setScale(2).toPlainString(), t.getNote());
        }
    }

    /**
     * A template's name (not blank), amount (as the claim form takes it for a claim that corrects none:
     * 0.00 or more, at most two decimals) and note.
     */
    public record TemplateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            @Size(max = 500) String note) {

        String nameValue() {
            return name.strip();
        }

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }

        String noteValue() {
            return note == null ? "" : note.strip();
        }
    }

    /** The name of the template a claim is made from. */
    public record FromTemplateRequest(@NotBlank @Size(max = 200) String name) {}

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public List<TemplateView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.templates(sheetId).stream().map(TemplateView::of).toList();
    }

    @PutMapping("/templates")
    public TemplateView save(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid TemplateRequest request) {
        return TemplateView.of(service.save(sheetId, user, request.nameValue(), request.amountValue(), request.noteValue()));
    }

    @PostMapping("/people/{personId}/claims/from-template")
    public ClaimView claimFrom(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FromTemplateRequest request) {
        return ClaimView.of(service.claimFrom(sheetId, user, personId, request.name().strip()));
    }
}
