package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** A grade's rate as the screen shows it; the percent always has two decimals; from is null from the start, to null with no end. */
    public record RateView(String grade, LocalDate from, LocalDate to, String pct) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getFromDate(), rate.getToDate(), rate.getPct().setScale(2).toPlainString());
        }
    }

    /** A percent of 0.00 or more, with at most two decimals, from a date (none: from the beginning of time) through a date (none: no end). */
    public record RateRequest(@NotNull @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String pct, LocalDate from, LocalDate to) {

        BigDecimal pctValue() {
            return new BigDecimal(pct).setScale(2);
        }
    }

    /** A rate preview as the screen shows it: how many claims' dues would change, and the sum of the differences (two decimals). */
    public record PreviewView(int count, String diff) {}

    private final RateService service;
    private final ClaimService claims;

    public RateController(RateService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    /** What the percent as the grade's rate on every date would change; nothing is stored. */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
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

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId, user, grade, request.from(), request.to(), request.pctValue()));
    }
}
