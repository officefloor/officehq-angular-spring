package net.officefloor.hq.app.paste;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The paste box: lines of name,amount,date,note, each added as a draft claim for the named person. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/paste-claims")
public class PasteController {

    public record PasteForm(@NotNull @Size(max = 200_000) String text) {}

    /** How many claims were added, and the claims, in the order of their lines. */
    public record PastedView(int added, List<ClaimView> claims) {}

    private final PasteService service;

    public PasteController(PasteService service) {
        this.service = service;
    }

    @PostMapping
    public PastedView paste(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @Valid @RequestBody PasteForm form) {
        List<ClaimView> claims = service.paste(user, sheetId, form.text()).stream().map(ClaimView::of).toList();
        return new PastedView(claims.size(), claims);
    }
}
