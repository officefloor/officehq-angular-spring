package net.officefloor.hq.app.rate;

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

/** Rates on a sheet, one per grade. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /** The rate is sent as text with exactly two decimals, so it is never rounded as a float. */
    public record RateView(String grade, String rate) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getRate().toPlainString());
        }
    }

    /** The rate is a percent of 0 or more, below 1000, with at most two decimals. */
    public record RateRequest(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String rate) {
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
            @RequestBody @Validated RateRequest request) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, new BigDecimal(request.rate())));
    }
}
