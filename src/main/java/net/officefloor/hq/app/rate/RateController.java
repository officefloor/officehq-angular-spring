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

/** The sheet's rate for each grade. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** A grade's rate as the screen shows it: its from-date (YYYY-MM-DD, none when it applies from
     *  the beginning of time) and a percent with two decimals. */
    public record RateView(String grade, String from, String rate) {

        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getFromDate() == null ? null : rate.getFromDate().toString(),
                    rate.getRate().toPlainString());
        }
    }

    public record RateRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) BigDecimal rate,
            LocalDate from) {
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The sheet's rates, by grade, each with its from-date; a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    /** Set a grade's rate from a date (or from the beginning of time), replacing the one it had then. */
    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, request.from(), request.rate()));
    }
}
