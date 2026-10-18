package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /**
     * A grade's rate from a date (null: from the beginning of time); the percent is sent as text with
     * two decimals so it is never rounded on the way.
     */
    public record RateView(Grade grade, String pct, LocalDate from) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2).toPlainString(), rate.getFromDate());
        }
    }

    /** The percent is 0.00 or more with up to two decimals; the from-date is optional. */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String pct, LocalDate from) {

        BigDecimal pctValue() {
            return new BigDecimal(pct).setScale(2);
        }
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The sheet's rates, by grade then from-date. */
    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    /** Set a grade's rate from a date, replacing the one it had for that from-date. */
    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @PathVariable Grade grade,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody RateForm form) {
        return RateView.of(service.set(sheetId, user, grade, form.from(), form.pctValue()));
    }
}
