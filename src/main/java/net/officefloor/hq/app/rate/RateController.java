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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /**
     * What the screen is told about a grade's rate; the percent with two decimals, and the date it
     * applies from (null: from the beginning of time) and through (null: no end).
     */
    public record RateView(String grade, String pct, LocalDate from, LocalDate to) {

        public static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().toPlainString(), rate.getFromDate(),
                    rate.getToDate());
        }
    }

    /**
     * A rate as entered on the form: a percent of 0.00 or more with up to two decimals, and the date
     * it applies from (none: from the beginning of time) and through (none: no end).
     */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String pct, LocalDate from,
            LocalDate to) {
    }

    private final RateService service;
    private final SheetHistory history;

    public RateController(RateService service, SheetHistory history) {
        this.service = service;
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
     * Set the rate for a grade from a date (through a to-date, if given), replacing any it had from
     * that date; a to-date before the from-date is refused.
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
}
