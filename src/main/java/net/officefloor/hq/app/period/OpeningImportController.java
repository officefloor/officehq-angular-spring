package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.period.OpeningImportService.ImportResult;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The paste box for opening balances. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/openings/import")
public class OpeningImportController {

    /** The pasted text: one opening balance per line, as name,period,amount. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** What a paste did: how many lines went through, and the periods they changed. */
    public record ImportView(int added, List<PeriodView> periods) {
    }

    private final OpeningImportService imports;

    public OpeningImportController(OpeningImportService imports) {
        this.imports = imports;
    }

    @PostMapping
    public ImportView importOpenings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm request) {
        ImportResult result = imports.importOpenings(sheetId, user, request.text());
        return new ImportView(result.lines(), result.periods().stream().map(PeriodView::of).toList());
    }
}
