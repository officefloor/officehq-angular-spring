package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import org.springframework.web.server.ResponseStatusException;

/** The rates for each grade on a sheet, each from its from-date. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /**
     * A rate as shown: the percent with two decimals, and its from- and to-dates as yyyy-mm-dd (null when it has none);
     * how many versions it has had (the one shown being the newest, in force); and its level, 0 for the sheet's own, 1 for its parent sheet's, 2 for that sheet's parent's and so on, the sheet it was cloned from's (and that one's parents' and source's) further along;
     * and, when it is forward-only, the highest claim id there was when it was saved (null otherwise), with its older
     * versions, newest first, that a claim added before then falls back on.
     */
    public record RateView(String grade, String pct, String from, String to, int version, int level, Long forwardAfter,
            List<OlderView> older) {

        static RateView of(Rate rate) {
            return of(rate, 0);
        }

        static RateView of(Rate rate, int level) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    rate.getFromDate() == null ? null : rate.getFromDate().toString(),
                    rate.getToDate() == null ? null : rate.getToDate().toString(), rate.getVersion(), level,
                    rate.getForwardAfter(), rate.getOlder().stream()
                            .sorted(Comparator.comparingInt(RateVersion::getVersion).reversed())
                            .map(OlderView::of).toList());
        }
    }

    /** An older version of a forward-only rate: its percent, to-date and, when it was forward-only too, its mark. */
    public record OlderView(String pct, String to, Long forwardAfter) {

        static OlderView of(RateVersion version) {
            return new OlderView(version.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    version.getToDate() == null ? null : version.getToDate().toString(), version.getForwardAfter());
        }
    }

    /**
     * A percent of 0.00 up to 999.99 with up to two decimals, and an optional from-date as yyyy-mm-dd;
     * a blank or missing from-date means the rate applies from the beginning of time. An optional to-date likewise:
     * blank or missing means the rate has no end.
     */
    public record RateRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String to) {

        BigDecimal pctValue() {
            return new BigDecimal(pct.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }

        LocalDate fromValue() {
            return date(from);
        }

        LocalDate toValue() {
            return date(to);
        }

        static LocalDate date(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    /** Lines of grade,rate,from,to to set as rates. */
    public record ImportRequest(@NotNull @Size(max = 1000000) String text) {
    }

    /** How many rates an import set. */
    public record ImportResult(int set) {
    }

    private final RateService service;
    private final ImportRatesService imports;

    public RateController(RateService service, ImportRatesService imports) {
        this.service = service;
        this.imports = imports;
    }

    /**
     * The sheet's rates in grade order, then by from-date (the undated one first); a grade with no rate is left out.
     * Then, likewise, the rates of its parent sheet, of that sheet's parent and so on up to three levels up, each
     * with its level; then, when the sheet was cloned, those of the sheet it was cloned from, its parents and its own
     * source and so on.
     */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        List<List<Rate>> chain = service.chain(sheetId.trim());
        List<RateView> views = new ArrayList<>();
        for (int level = 0; level < chain.size(); level++) {
            for (Rate rate : chain.get(level)) {
                views.add(RateView.of(rate, level));
            }
        }
        return views;
    }

    /**
     * What setting the grade's rate to the percent on every date would change, saving nothing: how many OPEN and PENDING
     * claims would show a different due, and the sum of the differences.
     */
    @GetMapping("/preview")
    public RollupService.RatePreview preview(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestParam @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.preview(sheetId.trim(), grade, new BigDecimal(pct.trim()).setScale(2, RoundingMode.UNNECESSARY));
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, request.fromValue(),
                request.toValue(), request.pctValue()));
    }

    /** Set a rate per pasted line, in order, each as the rate form would; refused as a whole when any line is. */
    @PostMapping("/import")
    public ImportResult importRates(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid ImportRequest request) {
        return new ImportResult(imports.importRates(sheetId.trim(), user.trim(), request.text()));
    }

    /**
     * Withdraw the newest version of the grade's rate from the from-date (missing or blank: its undated rate); the one
     * before it applies again, or the rate is gone. Refused for a non-steward.
     */
    @DeleteMapping("/{grade}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestParam(required = false) @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        service.withdraw(sheetId.trim(), user.trim(), grade, RateRequest.date(from));
    }
}
