package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.session.CurrentUser;
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

    /** A grade's rate as the screen shows it: the percent always with two decimals. */
    public record RateView(String grade, String pct) {
        static RateView of(Rate r) {
            return new RateView(r.getGrade(), r.getPct().setScale(2).toPlainString());
        }
    }

    /** A rate as the form submits it: a percent of 0.00 or more with up to two decimals. */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String pct) {
    }

    private final RateService service;

    public RateController(RateService service) {
        this.service = service;
    }

    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm body) {
        return RateView.of(service.set(sheetId, user.trim(), grade, new BigDecimal(body.pct())));
    }
}
