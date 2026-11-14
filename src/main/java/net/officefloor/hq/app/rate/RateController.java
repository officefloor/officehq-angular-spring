package net.officefloor.hq.app.rate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.AsOf;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** A grade's rate as the screen shows it; the rate is a percent string with two decimals, the
     *  from-date null when the rate applies from the beginning of time, the version its version count.
     *  forwardAfter is, for a forward-only rate, the id of the newest claim there was when it was saved
     *  (it applies only to claims with a higher id), else null; for a forward-only rate, earlier is its
     *  older versions, newest first, for the claims it does not apply to; else none. */
    public record RateView(String grade, String rate, LocalDate from, LocalDate to, int version, Long forwardAfter,
            List<EarlierView> earlier) {
    }

    /** An older version of a rate: its value, to-date and forward-only mark as {@link RateView} has them. */
    public record EarlierView(String rate, LocalDate to, Long forwardAfter) {
    }

    private RateView view(Rate rate) {
        List<EarlierView> earlier = rate.getForwardAfter() == null ? List.of()
                : service.earlier(rate).stream().map(version -> new EarlierView(version.getRate().toPlainString(),
                        version.getTo(), version.getForwardAfter())).toList();
        return new RateView(rate.getGrade(), rate.getRate().toPlainString(), rate.getFrom(), rate.getTo(),
                rate.getVersion(), rate.getForwardAfter(), earlier);
    }

    /** A rate: a percent of 0.00 or more with up to two decimals, and the date it applies from
     *  (blank: from the beginning of time) and the last date it applies on (blank: no end). */
    public record RateRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal rate,
            LocalDate from, LocalDate to) {
    }

    /** A rate preview as the screen shows it: how many claims would show a different due, and the
     *  sum of the differences with two decimals. */
    public record RatePreviewView(int count, String diff) {
    }

    /** Pasted rates, one per line as grade,rate,from,to. */
    public record ImportRequest(@NotNull @Size(max = 1_000_000) String text) {
    }

    private final RateService service;
    private final RateImportService imports;
    private final RollupService rollups;
    private final ClaimService claims;

    public RateController(RateService service, RateImportService imports, RollupService rollups,
            ClaimService claims) {
        this.service = service;
        this.imports = imports;
        this.rollups = rollups;
        this.claims = claims;
    }

    /** The sheet's rates by grade, each grade's from the earliest; a grade with no rate is not listed.
     *  As they stand now, or as they stood at the end of the screen's as-of date. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOf) {
        return service.list(sheetId.trim(), AsOf.parse(asOf)).stream().map(this::view).toList();
    }

    /** The rates of the sheets the sheet's rates fall back to, nearest first (its parent, the
     *  parent's parent, up to three levels up, then the sheet it was cloned from and its own), each
     *  listed as {@link #list} lists them as they stand now; under the screen's as-of date, the
     *  parents the sheet had at the end of that day. */
    @GetMapping("/inherited")
    public List<List<RateView>> inherited(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOf) {
        LocalDate day = AsOf.parse(asOf);
        return service.fallbacks(sheetId.trim(), day).stream()
                .map(ancestor -> service.list(ancestor).stream().map(this::view).toList())
                .toList();
    }

    /** Set the rate for a grade (A, B or C) from a date, replacing the one it had from that date. */
    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated RateRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return view(rollups.track(sheet, by, () -> service.set(sheet, by, grade, request.from(),
                request.to(), request.rate())));
    }

    /** Set a rate per pasted line, in order, each as the form would; none when any line is refused.
     *  The rates set, in the order of their lines. */
    @PostMapping("/import")
    public List<RateView> importRates(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated ImportRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return imports.importRates(sheet, by, request.text()).stream().map(this::view).toList();
    }

    /** Preview a candidate rate for a grade (A, B or C): how the dues of the OPEN and PENDING claims of
     *  people on the grade would change were it the grade's rate on every date. Nothing is saved. */
    @GetMapping("/{grade}/preview")
    public RatePreviewView preview(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestParam @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String rate) {
        ClaimService.RatePreview preview = claims.previewRate(sheetId.trim(), grade, new BigDecimal(rate).setScale(2));
        return new RatePreviewView(preview.count(), preview.diff().toPlainString());
    }

    /** A rate that is not a number or a from-date that is not a real date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }

    /** A to-date before the from-date, or a refused line of a paste, is refused. */
    @ExceptionHandler(RateRefusedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void refused() {
    }

    /** The grade's rate from that date was first set concurrently; caught by the unique index. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict() {
    }
}
