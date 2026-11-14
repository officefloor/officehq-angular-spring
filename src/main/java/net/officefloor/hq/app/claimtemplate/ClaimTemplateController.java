package net.officefloor.hq.app.claimtemplate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/claim-templates")
public class ClaimTemplateController {

    /** A claim template as the screen shows it; the amount is a string with two decimals. */
    public record ClaimTemplateView(Long id, String name, String amount, String note) {
        static ClaimTemplateView of(ClaimTemplate template) {
            return new ClaimTemplateView(template.getId(), template.getName(), template.getAmount().toPlainString(),
                    template.getNote());
        }
    }

    /** A template's fields: a name, an amount with up to two decimals, and a note (may be empty). */
    public record ClaimTemplateRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 2000) String note) {
    }

    private final ClaimTemplateService service;

    public ClaimTemplateController(ClaimTemplateService service) {
        this.service = service;
    }

    /** The sheet's claim templates, sorted by name. */
    @GetMapping
    public List<ClaimTemplateView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(ClaimTemplateView::of).toList();
    }

    /** Save a template; saving a name again replaces that template. */
    @PutMapping
    public ClaimTemplateView save(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated ClaimTemplateRequest request) {
        return ClaimTemplateView.of(service.save(sheetId.trim(), SignedInUser.decode(user), request.name(),
                request.amount(), request.note()));
    }

    /** A blank name, or an amount the claim form refuses, is refused. */
    @ExceptionHandler({ClaimTemplateRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
