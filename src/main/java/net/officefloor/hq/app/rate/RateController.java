package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** A grade's rate as the screen shows it; the percent always carries two decimals. */
    public record RateView(String grade, String pct) {
        static RateView of(Rate r) {
            return new RateView(r.getGrade(), r.getPct().toPlainString());
        }
    }

    /** A rate as the form submits it: a percent of 0.00 or more with up to two decimals. */
    public record RateForm(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal pct) {
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    /** The rates set on the sheet, by grade; a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody RateForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return RateView.of(service.set(user, sheetId, grade, form.pct()));
    }
}
