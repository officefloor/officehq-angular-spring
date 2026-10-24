package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.SheetHistory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The rates of a sheet's grades. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /** A grade's rate, the date it applies from (null: from the beginning of time) and the date it applies
     * through (null: with no end); the percent is
     * sent as text with two decimals so it is never rounded on the way; its level is 0 for the sheet's own
     * rate, 1 for its parent's, 2 for its parent's parent's, and so on (see {@link RateChain}). */
    public record RateView(String grade, String pct, LocalDate from, LocalDate to, int level) {
        static RateView of(Rate rate) {
            return of(rate, 0);
        }

        static RateView of(Rate rate, int level) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    rate.getFrom(), rate.getTo(), level);
        }
    }

    /** A rate: a percent of 0.00 or more, with up to two decimals, and the date it applies from (null: from
     * the beginning of time) and through (null: with no end), which is not before its from-date. */
    public record RateRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct,
            LocalDate from, LocalDate to) {

        BigDecimal pctValue() {
            return new BigDecimal(pct.trim()).setScale(2);
        }
    }

    private final RateService service;

    private final SheetHistory history;

    public RateController(RateService service, SheetHistory history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<List<Rate>> levels = asOf == null ? service.levels(sheetId.trim())
                : history.read(sheetId.trim(), asOf).rateLevels();
        List<RateView> views = new ArrayList<>();
        for (int level = 0; level < levels.size(); level++) {
            for (Rate rate : levels.get(level)) views.add(RateView.of(rate, level));
        }
        return views;
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, request.from(), request.to(), request.pctValue()));
    }

    /** A rate preview: how many claims would show a different due, and the sum of the differences as
     * text with two decimals. */
    public record PreviewView(int count, String diff) {
    }

    @GetMapping("/{grade}/preview")
    public PreviewView preview(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct) {
        RateService.Preview preview = service.preview(sheetId.trim(), grade, new BigDecimal(pct.trim()).setScale(2));
        return new PreviewView(preview.count(), preview.diff().toPlainString());
    }
}
