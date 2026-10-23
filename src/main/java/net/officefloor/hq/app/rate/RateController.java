package net.officefloor.hq.app.rate;

import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.history.AsOfGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /**
     * What the screen is told about a grade's rate; the percent with two decimals, and the date it
     * applies from (null: from the beginning of time) and through (null: no end), and how many
     * versions it has (null in views kept before rates had versions: one). A forward-only rate carries
     * where it was saved in the order claims are added (null: it applies to every claim), and its older
     * versions, newest first, are what a claim added before it was saved falls back to (null in views
     * kept before forward-only rates: none).
     */
    public record RateView(String grade, String pct, LocalDate from, LocalDate to, Integer versions,
            Long forwardAfter, List<OlderView> older) {

        public static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().toPlainString(), rate.getFromDate(),
                    rate.getToDate(), rate.getVersion(), rate.getForwardAfter(),
                    rate.getOlder().stream().map(OlderView::of).toList());
        }
    }

    /** An older version of a rate: its percent with two decimals, to-date, and where it was saved when forward-only. */
    public record OlderView(String pct, LocalDate to, Long forwardAfter) {

        public static OlderView of(RateVersion version) {
            return new OlderView(version.getPct().toPlainString(), version.getToDate(), version.getForwardAfter());
        }
    }

    /**
     * A rate as entered on the form: a percent of 0.00 or more with up to two decimals, and the date
     * it applies from (none: from the beginning of time) and through (none: no end).
     */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String pct, LocalDate from,
            LocalDate to) {
    }

    /** The rate import box's text: lines of grade,rate,from,to. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** What the screen is told of a rate import: how many rates were set, and the sheet's rates after. */
    public record ImportView(int set, List<RateView> rates) {
    }

    private final RateService service;
    private final RateImportService imports;
    private final SheetHistory history;

    public RateController(RateService service, RateImportService imports, SheetHistory history) {
        this.service = service;
        this.imports = imports;
        this.history = history;
    }

    /**
     * The sheet's rates by grade, each grade's by from-date; a grade with no rate is not listed. With an
     * as-of date, as they stood at the end of it.
     */
    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf) {
        return history.views(sheetId, AsOfGuard.date(asOf)).map(SheetHistory.Views::rates)
                .orElseGet(() -> service.list(sheetId).stream().map(RateView::of).toList());
    }

    /**
     * The rates of the sheet's ancestors, nearest first (its parent's, then its parent's parent's, up to
     * three levels up), each sheet's by grade and from-date: what a grade with no rate in force on the
     * sheet falls back to. With an as-of date, each as it stood at the end of it.
     */
    @GetMapping("/inherited")
    public List<List<RateView>> inherited(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) String asOf) {
        return history.inherited(sheetId, AsOfGuard.date(asOf)).stream()
                .map(rates -> rates.stream().map(RateView::of).toList()).toList();
    }

    /**
     * Set the rate for a grade from a date (through a to-date, if given), as a new version of any it
     * had from that date; a to-date before the from-date is refused.
     */
    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RateForm form) {
        return RateView.of(service.set(sheetId, SignedIn.name(user), grade, form.from(),
                form.to(), new BigDecimal(form.pct())));
    }

    /**
     * Set a rate per imported line, in order, each as the rate form would; nothing is set when any line
     * is refused.
     */
    @PostMapping("/import")
    public ImportView importRates(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid ImportForm form) {
        int set = imports.importRates(sheetId, SignedIn.name(user), form.text()).size();
        return new ImportView(set, service.list(sheetId).stream().map(RateView::of).toList());
    }

    /** What the screen is told of a rate preview: the claims whose due would change, and the sum of the differences. */
    public record PreviewView(int count, String diff) {
    }

    /**
     * How many OPEN and PENDING claims on a grade would show a different due were a candidate rate
     * applied to the grade on every date, and the sum of the differences; nothing is saved.
     */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestParam @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String pct) {
        RateService.Preview preview = service.preview(sheetId, grade, new BigDecimal(pct));
        return new PreviewView(preview.count(), preview.diff().toPlainString());
    }
}
