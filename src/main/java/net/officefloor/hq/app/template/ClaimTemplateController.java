package net.officefloor.hq.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimTemplateController {

    /** A template as the screen shows it: its name, its amount with two decimals and its note. */
    public record TemplateView(String name, String amount, String note) {
        static TemplateView of(ClaimTemplate t) {
            return new TemplateView(t.getName(), t.getAmount().setScale(2).toPlainString(), t.getNote());
        }
    }

    /** A template as the form submits it: a name (not blank), an amount the claim form takes for
     *  a new claim (0.00 or more, up to two decimals), and an optional note. */
    public record TemplateForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*\\d{1,13}(\\.\\d{1,2})?\\s*") String amount,
            @Size(max = 1000) String note) {
    }

    /** The name of the template a claim is made from. */
    public record FromTemplateForm(@NotBlank @Size(max = 200) String template) {
    }

    private final ClaimTemplateService service;
    private final ClaimController claims;

    public ClaimTemplateController(ClaimTemplateService service, ClaimController claims) {
        this.service = service;
        this.claims = claims;
    }

    @GetMapping("/templates")
    public List<TemplateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(TemplateView::of).toList();
    }

    /** Save a template; resolves to the sheet's templates with it. */
    @PutMapping("/templates")
    public List<TemplateView> save(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TemplateForm body) {
        service.save(sheetId, user.trim(), body.name().trim(), new BigDecimal(body.amount().trim()), body.note());
        return list(sheetId, user);
    }

    @PostMapping("/people/{personId}/claims/from-template")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView addClaim(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable long personId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FromTemplateForm body) {
        return claims.view(service.addClaim(sheetId, user.trim(), personId, body.template().trim()));
    }
}
