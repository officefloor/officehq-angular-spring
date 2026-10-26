package net.officefloor.hq.app.paste;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/claims/paste")
public class PasteController {

    /** The paste box: lines of name,amount,date,note. */
    public record PasteForm(@NotNull @Size(max = 100_000) String text) {
    }

    private final PasteService service;

    public PasteController(PasteService service) {
        this.service = service;
    }

    /** The claims added, in the order of their lines. */
    @PostMapping
    public List<ClaimView> paste(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PasteForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return service.paste(user, sheetId, form.text()).stream().map(ClaimView::of).toList();
    }
}
