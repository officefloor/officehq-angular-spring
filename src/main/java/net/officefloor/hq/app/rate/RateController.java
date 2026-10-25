package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.person.Grade;
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

    /** What the screen shows of a rate; the percent always has two decimals. No from-date means the
     *  rate applies from the beginning of time. */
    public record RateView(Grade grade, String pct, LocalDate from) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().toPlainString(), rate.getFrom());
        }
    }

    /** A rate's percent and from-date, as entered on the form; no from-date means from the beginning of time. */
    public record RateForm(@NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal pct,
            LocalDate from) {
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The sheet's rates in grade order, then by from-date; a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(RateView::of).toList();
    }

    /** Set the rate for a grade from a from-date, replacing the one it had for that from-date. */
    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable Grade grade,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RateForm form) {
        return RateView.of(service.set(sheetId.trim(), SignedInUser.name(user), grade, form.from(), form.pct()));
    }
}
