package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.asof.AsOfGuard;
import net.officefloor.hq.app.claim.ClaimService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /**
     * A grade's rate as the screen shows it, with its version count; the percent always has two decimals; from is null
     * from the start, to null with no end; forwardAfter, when forward-only, the last place in the order claims were
     * added when it was saved (null: it applies to every claim).
     */
    public record RateView(String grade, LocalDate from, LocalDate to, String pct, int versions, Long forwardAfter) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getFromDate(), rate.getToDate(), rate.getPct().setScale(2).toPlainString(),
                    rate.getVersions(), rate.getForwardAfter());
        }
    }

    /** A percent of 0.00 or more, with at most two decimals, from a date (none: from the beginning of time) through a date (none: no end). */
    public record RateRequest(@NotNull @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String pct, LocalDate from, LocalDate to) {

        BigDecimal pctValue() {
            return new BigDecimal(pct).setScale(2);
        }
    }

    /** Pasted lines of grade,rate,from,to, one rate each. */
    public record ImportRequest(@NotNull @Size(max = 200000) String text) {}

    /** How many rates an import set, and each as the store now holds it, in the order of their lines. */
    public record ImportView(int set, List<RateView> rates) {}

    /** A rate preview as the screen shows it: how many claims' dues would change, and the sum of the differences (two decimals). */
    public record PreviewView(int count, String diff) {}

    private final RateService service;
    private final ClaimService claims;
    private final RateImportService importer;

    public RateController(RateService service, ClaimService claims, RateImportService importer) {
        this.service = service;
        this.claims = claims;
        this.importer = importer;
    }

    /** What the percent as the grade's rate on every date would change; nothing is stored. */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String pct) {
        ClaimService.RatePreview preview = claims.previewRate(sheetId, grade, new BigDecimal(pct).setScale(2));
        return new PreviewView(preview.count(), preview.diff().setScale(2).toPlainString());
    }

    @GetMapping
    public List<RateView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    /**
     * The rates of the sheet's ancestors, nearest first (up to three levels up, then for a clone the sheet it was
     * cloned from, its parents and its source, and so on), that a grade with no rate of its own in force on a date takes; as of the user's as-of date, each as it stood at the end of it.
     */
    @GetMapping("/inherited")
    public List<List<RateView>> inherited(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String asOf) {
        LocalDate date = asOf == null || asOf.isBlank() ? null : LocalDate.parse(asOf.strip());
        return service.ancestors(sheetId, date).stream()
                .map(level -> level.values().stream().flatMap(List::stream).map(RateView::of).toList())
                .toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId, user, grade, request.from(), request.to(), request.pctValue()));
    }

    /** Set a rate per pasted line, in order, each as the form sets it; a line refused refuses them all. */
    @PostMapping("/import")
    public ImportView importRates(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ImportRequest request) {
        List<RateView> set = importer.importRates(sheetId, user, request.text()).stream().map(RateView::of).toList();
        return new ImportView(set.size(), set);
    }
}
