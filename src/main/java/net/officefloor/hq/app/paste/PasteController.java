package net.officefloor.hq.app.paste;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The paste box for claims. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/claims/paste")
public class PasteController {

    /** The pasted text: one claim per line, as name,amount,date,note. */
    public record PasteForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** What a paste did: how many claims it added and the claims, in the order added. */
    public record PasteView(int added, List<ClaimView> claims) {
    }

    private final PasteService paste;

    public PasteController(PasteService paste) {
        this.paste = paste;
    }

    @PostMapping
    public PasteView paste(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PasteForm request) {
        List<ClaimView> added = paste.paste(sheetId, user, request.text()).stream().map(ClaimView::of).toList();
        return new PasteView(added.size(), added);
    }
}
