package net.officefloor.hq.app.rate;

import org.springframework.format.annotation.DateTimeFormat;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.rollup.Rollup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rates on a sheet: each grade's, by from-date. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** A grade's rate as the screen shows it: a percent with exactly two decimals, and the day it
     *  takes effect (null when it applies from the beginning of time) and the last day it applies
     *  (null when it does not end). */
    public record RateView(String grade, String rate, LocalDate from, LocalDate to) {

        public static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getRate().toPlainString(), rate.getFromDate(),
                    rate.getToDate());
        }
    }

    /** A rate as the form submits it: a percent of 0.00 or more with up to two decimals, and an
     *  optional from-date and to-date. */
    public record RateForm(@NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal rate,
            LocalDate from, LocalDate to) {
    }

    private final RateService rates;
    private final SheetHistory history;

    public RateController(RateService rates, SheetHistory history) {
        this.history = history;
        this.rates = rates;
    }

    /** What a candidate rate for a grade, applied on every date, would change; nothing is saved. */
    @GetMapping("/{grade}/preview")
    public Rollup.RatePreview preview(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestParam @NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal rate) {
        return rates.preview(sheetId, grade, rate);
    }

    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        if (asOf != null) {
            return history.viewsAsOf(sheetId, asOf).rates();
        }
        return rates.list(sheetId).stream().map(RateView::of).toList();
    }

    /** The rates of the sheet's ancestors, nearest first (its parent's, its parent's parent's, up to three levels
     *  up), each by grade in the order they take effect: a grade with no rate in force on a date on the sheet takes
     *  the first in force on that date up the line. As of a date, each as it stood then. */
    @GetMapping("/inherited")
    public List<List<RateView>> inherited(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(value = SheetHistory.AS_OF_HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return rates.ancestorsOf(sheetId).stream()
                .map(ancestor -> asOf != null ? history.viewsAsOf(ancestor, asOf).rates()
                        : rates.list(ancestor).stream().map(RateView::of).toList())
                .toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm request) {
        return RateView.of(rates.set(sheetId, user, grade, request.from(), request.to(), request.rate()));
    }
}
