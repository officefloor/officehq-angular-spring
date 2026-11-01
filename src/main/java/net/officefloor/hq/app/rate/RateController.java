package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** A grade's rate as the screen shows it; the percent always has two decimals. */
    public record RateView(String grade, String pct) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2).toPlainString());
        }
    }

    /** A percent of 0.00 or more, with at most two decimals. */
    public record RateRequest(@NotNull @Pattern(regexp = "\\d{1,5}(\\.\\d{1,2})?") String pct) {

        BigDecimal pctValue() {
            return new BigDecimal(pct).setScale(2);
        }
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    @GetMapping
    public List<RateView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid RateRequest request) {
        return RateView.of(service.set(sheetId, user, grade, request.pctValue()));
    }
}
