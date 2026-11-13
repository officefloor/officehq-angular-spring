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
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** A grade's rate as the screen shows it; the rate is a percent string with two decimals, the
     *  from-date null when the rate applies from the beginning of time. */
    public record RateView(String grade, String rate, LocalDate from, LocalDate to) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getRate().toPlainString(), rate.getFrom(), rate.getTo());
        }
    }

    /** A rate: a percent of 0.00 or more with up to two decimals, and the date it applies from
     *  (blank: from the beginning of time) and the last date it applies on (blank: no end). */
    public record RateRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal rate,
            LocalDate from, LocalDate to) {
    }

    private final RateService service;
    private final RollupService rollups;

    public RateController(RateService service, RollupService rollups) {
        this.service = service;
        this.rollups = rollups;
    }

    /** The sheet's rates by grade, each grade's from the earliest; a grade with no rate is not listed.
     *  As they stand now, or as they stood at the end of the screen's as-of date. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOf) {
        return service.list(sheetId.trim(), AsOf.parse(asOf)).stream().map(RateView::of).toList();
    }

    /** Set the rate for a grade (A, B or C) from a date, replacing the one it had from that date. */
    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated RateRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return RateView.of(rollups.track(sheet, by, () -> service.set(sheet, by, grade, request.from(),
                request.to(), request.rate())));
    }

    /** A rate that is not a number or a from-date that is not a real date is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }

    /** A to-date before the from-date is refused. */
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
