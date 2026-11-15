package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/paste-claims")
@Validated
public class PasteClaimsController {

    /** The paste box's text: lines of name,amount,date,note. */
    public record PasteForm(@NotNull @Size(max = 1_000_000) String text) {
    }

    private final PasteClaimsService service;
    private final ClaimService claims;

    public PasteClaimsController(PasteClaimsService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    /** The claims added, in the order of their lines. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<ClaimView> paste(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PasteForm body) {
        return service.paste(sheetId, user.trim(), body.text()).stream()
                .map(c -> ClaimView.of(c, claims.logOf(c.getId()))).toList();
    }
}
