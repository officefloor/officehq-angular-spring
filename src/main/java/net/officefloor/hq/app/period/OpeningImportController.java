package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/periods/openings")
@Validated
public class OpeningImportController {

    /** The opening balances box: lines of name,period,amount. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many lines an import put through, and the periods it changed. */
    public record Imported(int added, List<PeriodView> periods) {
    }

    private final OpeningImportService service;
    private final SheetService sheets;

    public OpeningImportController(OpeningImportService service, SheetService sheets) {
        this.service = service;
        this.sheets = sheets;
    }

    @PostMapping
    public Imported importOpenings(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm form) {
        OpeningImportService.Imported imported = service.importOpenings(sheetId.trim(), user.trim(), form.text());
        int fiscalStartMonth = sheets.fiscalStartMonth(sheetId.trim());
        return new Imported(imported.added(),
                imported.periods().stream().map(p -> PeriodView.of(p, fiscalStartMonth)).toList());
    }
}
