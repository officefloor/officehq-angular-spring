package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The rate for each grade on a sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** The rate is a percent, 0.00 or more, with up to two decimals. */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String rate) {}

    /** The rate is shown with two decimals. */
    public record RateView(String grade, String rate) {

        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getRate().setScale(2).toPlainString());
        }
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    @GetMapping
    public List<RateView> list(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade:[ABC]}")
    public RateView set(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable String grade, @Valid @RequestBody RateForm form) {
        return RateView.of(service.set(user, sheetId, grade, new BigDecimal(form.rate())));
    }
}
