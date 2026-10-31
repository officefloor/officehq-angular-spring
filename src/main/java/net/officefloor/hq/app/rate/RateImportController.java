package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.rate.RateController.RateView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The import box for rates. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates/import")
public class RateImportController {

    /** The pasted text: one rate per line, as grade,rate,from,to. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** What an import did: how many rates it set, and those rates, in line order. */
    public record ImportView(int set, List<RateView> rates) {
    }

    private final RateImportService imports;

    public RateImportController(RateImportService imports) {
        this.imports = imports;
    }

    @PostMapping
    public ImportView importRates(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm request) {
        List<RateView> views = imports.importRates(sheetId, user, request.text()).stream().map(RateView::of).toList();
        return new ImportView(views.size(), views);
    }
}
