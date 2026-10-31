package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.RatePreviewService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The rates for each grade on a sheet, each from its from-date. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /**
     * A grade's rate as shown: its from-date (null: from the beginning of time), a percent with two decimals,
     * how many versions it has had, and whether it is forward-only (applies only to claims added after it was saved).
     */
    public record RateView(String grade, LocalDate from, LocalDate to, String pct, int version, boolean forwardOnly) {

        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getFromDate(), rate.getToDate(),
                    rate.getPct().setScale(2, RoundingMode.HALF_UP).toPlainString(), rate.getVersion(),
                    rate.getForwardAfter() != null);
        }
    }

    /**
     * A percent is 0.00 or more with up to two decimals; a blank from-date applies from the beginning of
     * time, a blank to-date has no end, and a to-date is not before the from-date.
     */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String pct, LocalDate from,
            LocalDate to) {

        @AssertTrue
        public boolean isEndAfterStart() {
            return from == null || to == null || !to.isBefore(from);
        }
    }

    /** A rate preview as shown: how many claims' dues would differ, and the sum of the differences (two decimals). */
    public record PreviewView(int count, String diff) {
    }

    /** Pasted lines of {@code grade,rate,from,to}. */
    public record ImportForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** How many rates an import set. */
    public record ImportView(int set) {
    }

    private final RateService service;
    private final RatePreviewService previews;
    private final RateImportService imports;

    public RateController(RateService service, RatePreviewService previews, RateImportService imports) {
        this.service = service;
        this.previews = previews;
        this.imports = imports;
    }

    /** What the candidate percent would do to the dues of the grade's OPEN and PENDING claims; nothing is saved. */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = GradeService.CODE) String grade,
            @RequestParam @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String pct) {
        RatePreviewService.Preview p = previews.preview(sheetId.trim(), grade, new BigDecimal(pct));
        return new PreviewView(p.count(), p.diff().toPlainString());
    }

    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    /**
     * The rates of the sheet's parent sheets, nearest first, up to three levels up: a grade with no rate in
     * force on the sheet takes the first of them that has one.
     */
    @GetMapping("/inherited")
    public List<List<RateView>> inherited(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.inherited(sheetId.trim()).stream().map(rs -> rs.stream().map(RateView::of).toList()).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = GradeService.CODE) String grade, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody RateForm form) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, form.from(), form.to(), new BigDecimal(form.pct())));
    }

    /** Set a rate per pasted line, in order, as the rate form sets it; one refused line sets none. */
    @PostMapping("/import")
    public ImportView importRates(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody ImportForm form) {
        return new ImportView(imports.importRates(sheetId.trim(), user.trim(), form.text()));
    }

    /** Another user set the same grade's rate for the same from-date at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void rateTaken() {
    }
}
