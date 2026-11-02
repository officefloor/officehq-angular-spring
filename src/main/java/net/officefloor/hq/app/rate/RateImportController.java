package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/rates/import")
@Validated
public class RateImportController {

    /** The rate import box: lines of grade,rate,from,to. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many rates an import set. */
    public record Imported(int set) {
    }

    private final RateImportService service;

    public RateImportController(RateImportService service) {
        this.service = service;
    }

    @PostMapping
    public Imported importRates(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm form) {
        return new Imported(service.importRates(sheetId.trim(), user.trim(), form.text()));
    }
}
