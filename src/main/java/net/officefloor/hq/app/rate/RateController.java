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

    /** A percent: 0.00 to 999.99, with up to two decimals. */
    private static final String PCT = "\\d{1,3}(\\.\\d{1,2})?";

    public record ListRatesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record SetRateRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Pattern(regexp = "[ABC]") String grade, @NotNull @Pattern(regexp = PCT) String pct,
            LocalDate from, LocalDate to) {
    }

    public record PreviewRateRequest(@NotBlank @Size(max = 100) String sheet,
            @NotNull @Pattern(regexp = "[ABC]") String grade, @NotNull @Pattern(regexp = PCT) String pct) {
    }

    public record PreviewRateResponse(int count, String diff) {
    }

    public record RateResponse(String grade, LocalDate from, LocalDate to, String pct) {
    }

    private static RateResponse response(Rate rate) {
        return new RateResponse(rate.getGrade(), rate.getFromDate(), rate.getToDate(),
                rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
    }

    private final RateService service;
    private final ClaimService claims;

    public RateController(RateService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    @PostMapping("/list")
    public List<RateResponse> list(@Valid @RequestBody ListRatesRequest req) {
        return service.list(req.sheet().trim()).stream().map(RateController::response).toList();
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
                new BigDecimal(req.pct()).setScale(2, RoundingMode.UNNECESSARY)));
    }
}
