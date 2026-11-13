package net.officefloor.hq.app.rate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
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

    /** A grade's rate as the screen shows it; the rate is a percent string with two decimals. */
    public record RateView(String grade, String rate) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getRate().toPlainString());
        }
    }

    /** A rate: a percent of 0.00 or more with up to two decimals. */
    public record RateRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal rate) {
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The sheet's rates by grade; a grade with no rate is not listed. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    /** Set the rate for a grade (A, B or C). */
    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), SignedInUser.decode(user), grade, request.rate()));
    }

    /** A rate that is not a number is refused. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }

    /** The grade's rate was first set concurrently; caught by the unique index. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict() {
    }
}
