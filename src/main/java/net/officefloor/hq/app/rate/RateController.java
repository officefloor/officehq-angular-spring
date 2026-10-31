package net.officefloor.hq.app.rate;

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

        static RateView of(Rate rate) {
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

    public RateController(RateService rates) {
        this.rates = rates;
    }

    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return rates.list(sheetId).stream().map(RateView::of).toList();
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
