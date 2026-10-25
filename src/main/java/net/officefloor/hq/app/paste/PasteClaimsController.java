package net.officefloor.hq.app.paste;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
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
@RequestMapping("/api/sheets/{sheetId}/claims/paste")
@Validated
public class PasteClaimsController {

    /** The paste box's text: lines of name,amount,date,note. */
    public record PasteForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many claims the paste added, and the claims, in the order of their lines. */
    public record PasteView(int added, List<ClaimView> claims) {
    }

    private final PasteClaimsService service;

    public PasteClaimsController(PasteClaimsService service) {
        this.service = service;
    }

    /** Add a draft claim per line, all or none. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PasteView paste(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PasteForm form) {
        List<ClaimView> claims = service.paste(sheetId.trim(), SignedInUser.name(user), form.text())
                .stream().map(ClaimView::of).toList();
        return new PasteView(claims.size(), claims);
    }
}
