package net.officefloor.hq.app.paste;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The claim paste box of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/claims/paste")
@Validated
public class PasteController {

    /** The pasted lines of {@code name,amount,date,note}. */
    public record PasteRequest(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many lines went through (each claim added or updated, and opened), and the claims, in the order of their lines. */
    public record PasteView(int added, List<ClaimView> claims) {
    }

    private final PasteService service;
    private final ClaimService claimService;

    public PasteController(PasteService service, ClaimService claimService) {
        this.service = service;
        this.claimService = claimService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PasteView paste(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PasteRequest request) {
        List<ClaimView> claims = ClaimView.allOf(service.paste(sheetId.trim(), user.trim(), request.text()), claimService);
        return new PasteView(claims.size(), claims);
    }
}
