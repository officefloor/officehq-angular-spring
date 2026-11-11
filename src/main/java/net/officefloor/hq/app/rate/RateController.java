package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The rates for each grade on a sheet, each from its from-date. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /** A rate as shown: the percent with two decimals, and its from- and to-dates as yyyy-mm-dd (null when it has none). */
    public record RateView(String grade, String pct, String from, String to) {

        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    rate.getFromDate() == null ? null : rate.getFromDate().toString(),
                    rate.getToDate() == null ? null : rate.getToDate().toString());
        }
    }

    /**
     * A percent of 0.00 up to 999.99 with up to two decimals, and an optional from-date as yyyy-mm-dd;
     * a blank or missing from-date means the rate applies from the beginning of time. An optional to-date likewise:
     * blank or missing means the rate has no end.
     */
    public record RateRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String pct,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String from,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String to) {

        BigDecimal pctValue() {
            return new BigDecimal(pct.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }

        LocalDate fromValue() {
            return date(from);
        }

        LocalDate toValue() {
            return date(to);
        }

        private static LocalDate date(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date: " + value.trim());
            }
        }
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The sheet's rates in grade order, then by from-date (the undated one first); a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    /**
     * What setting the grade's rate to the percent on every date would change, saving nothing: how many OPEN and PENDING
     * claims would show a different due, and the sum of the differences.
     */
    @GetMapping("/preview")
    public RollupService.RatePreview preview(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam @Pattern(regexp = "[ABC]") String grade,
            @RequestParam @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String pct,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.preview(sheetId.trim(), grade, new BigDecimal(pct.trim()).setScale(2, RoundingMode.UNNECESSARY));
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, request.fromValue(),
                request.toValue(), request.pctValue()));
    }
}
