package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /** What the screen shows of a rate; the percent as text with two decimals, and the from-date
     *  (null when it applies from the beginning of time). */
    public record RateView(String grade, String pct, LocalDate from) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2).toPlainString(), rate.getFromDate());
        }
    }

    /** The rate form: a percent of 0.00 or more with up to two decimals, and an optional from-date
     *  (left out: from the beginning of time). */
    public record RateForm(@NotNull @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct,
            LocalDate from) {

        BigDecimal pctValue() {
            return new BigDecimal(pct.trim()).setScale(2);
        }
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm form) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, form.pctValue(), form.from()));
    }
}
