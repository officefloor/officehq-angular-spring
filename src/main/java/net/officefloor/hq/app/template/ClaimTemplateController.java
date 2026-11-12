package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

/** A sheet's claim templates. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/claim-templates")
public class ClaimTemplateController {

    public record ClaimTemplateView(long id, String name, String amount, String note) {

        static ClaimTemplateView of(ClaimTemplate t) {
            return new ClaimTemplateView(t.getId(), t.getName(), t.getAmount().setScale(2).toPlainString(),
                    t.getNote());
        }
    }

    public record ClaimTemplateRequest(
            @Size(max = 200) String name,
            @NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 1000) String note) {
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    /** The sheet's claim templates, by name. */
    @GetMapping
    public List<ClaimTemplateView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(ClaimTemplateView::of).toList();
    }

    /** Save a template, replacing the one by its name. */
    @PostMapping
    public ClaimTemplateView save(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ClaimTemplateRequest request) {
        return ClaimTemplateView.of(
                service.save(sheetId.trim(), user.trim(), request.name(), request.amount(), request.note()));
    }

    /** A template that cannot be saved is refused with the reason, for the screen to show. */
    @ExceptionHandler(TemplateRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(TemplateRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
