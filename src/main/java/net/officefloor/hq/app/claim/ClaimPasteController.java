package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/claims/paste")
@Validated
public class ClaimPasteController {

    /** The paste box: lines of name,amount,date,note. */
    public record PasteForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many claims a paste added. */
    public record Pasted(int added) {
    }

    private final ClaimPasteService service;

    public ClaimPasteController(ClaimPasteService service) {
        this.service = service;
    }

    @PostMapping
    public Pasted paste(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PasteForm form) {
        return new Pasted(service.paste(sheetId.trim(), user.trim(), form.text()));
    }
}
