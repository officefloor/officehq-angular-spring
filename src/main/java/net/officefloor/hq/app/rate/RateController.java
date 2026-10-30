package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The rate for each grade on a sheet. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /** A grade's rate as shown: a percent with two decimals. */
    public record RateView(String grade, String pct) {

        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
    }

    /** A percent is 0.00 or more with up to two decimals. */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String pct) {
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
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody RateForm form) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, new BigDecimal(form.pct())));
    }

    /** Another user set the same grade's first rate at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void rateTaken() {
    }
}
