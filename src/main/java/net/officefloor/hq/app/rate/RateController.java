package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.claim.ClaimService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing and setting the sheet's rates for each grade, each from an optional from-date and through
 * an optional to-date.
 */
@RestController
@RequestMapping("/api/rates")
public class RateController {

    /** A percent: 0.00 to 99999.99, with up to two decimals. */
    private static final String PCT = "\\d{1,5}(\\.\\d{1,2})?";

    public record ListRatesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record SetRateRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Pattern(regexp = GradeService.CODE) String grade, @NotNull @Pattern(regexp = PCT) String pct,
            LocalDate from, LocalDate to) {
    }

    public record ImportRatesRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 100000) String text) {
    }

    /** What an import set: how many rates, and each one set, in line order. */
    public record ImportRatesResponse(int set, List<RateResponse> rates) {
    }

    public record PreviewRateRequest(@NotBlank @Size(max = 100) String sheet,
            @NotNull @Pattern(regexp = GradeService.CODE) String grade, @NotNull @Pattern(regexp = PCT) String pct) {
    }

    public record PreviewRateResponse(int count, String diff) {
    }

    /**
     * A rate with its version count; {@code level} 0 for the sheet's own, 1 for its parent's, 2 for the parent's parent, and so on;
     * {@code forwardAfter} for a forward-only rate, applying only to claims whose {@code addedSeq} is above it (none: every claim).
     */
    public record RateResponse(String grade, LocalDate from, LocalDate to, String pct, int level, int version,
            Long forwardAfter) {
    }

    private static RateResponse response(Rate rate, int level) {
        return new RateResponse(rate.getGrade(), rate.getFromDate(), rate.getToDate(),
                rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), level,
                rate.getVersion(), rate.getForwardAfter());
    }

    private final RateService service;
    private final RateImportService imports;
    private final ClaimService claims;

    public RateController(RateService service, RateImportService imports, ClaimService claims) {
        this.service = service;
        this.imports = imports;
        this.claims = claims;
    }

    /** The sheet's own rates, then those of each of its ancestors (see {@link RateChain}), each with its level. */
    @PostMapping("/list")
    public List<RateResponse> list(@Valid @RequestBody ListRatesRequest req) {
        List<List<Rate>> levels = service.list(req.sheet().trim());
        return IntStream.range(0, levels.size())
                .boxed().flatMap(level -> levels.get(level).stream().map(rate -> response(rate, level))).toList();
    }

    /** How many OPEN and PENDING claims a candidate rate for a grade would change, and by how much in all. Saves nothing. */
    @PostMapping("/preview")
    public PreviewRateResponse preview(@Valid @RequestBody PreviewRateRequest req) {
        ClaimService.RatePreview preview = claims.previewRate(req.sheet().trim(), req.grade(),
                new BigDecimal(req.pct()).setScale(2, RoundingMode.UNNECESSARY));
        return new PreviewRateResponse(preview.count(), preview.diff().toPlainString());
    }

    @PostMapping("/set")
    public RateResponse set(@Valid @RequestBody SetRateRequest req) {
        return response(service.set(req.sheet().trim(), req.user().trim(), req.grade(), req.from(), req.to(),
                new BigDecimal(req.pct()).setScale(2, RoundingMode.UNNECESSARY)), 0);
    }

    @PostMapping("/import")
    public ImportRatesResponse importRates(@Valid @RequestBody ImportRatesRequest req) {
        List<Rate> set = imports.importRates(req.sheet().trim(), req.user().trim(), req.text());
        return new ImportRatesResponse(set.size(), set.stream().map(rate -> response(rate, 0)).toList());
    }
}
