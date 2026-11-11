package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The rate for each grade on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /** A rate as shown: the percent with two decimals. */
    public record RateView(String grade, String pct) {

        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    /** A percent of 0.00 up to 999.99 with up to two decimals. */
    public record RateRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String pct) {

        BigDecimal pctValue() {
            return new BigDecimal(pct.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The sheet's rates in grade order; a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, request.pctValue()));
    }
}
