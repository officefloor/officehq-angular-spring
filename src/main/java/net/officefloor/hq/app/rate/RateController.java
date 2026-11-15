package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A grade's rate as the screen shows it: the percent always with two decimals, and its
     *  from-date (null when it applies from the beginning of time) and to-date (null: for good). */
    public record RateView(String grade, LocalDate from, LocalDate to, String pct) {
        static RateView of(Rate r) {
            return new RateView(r.getGrade(), r.getFrom(), r.getTo(), r.getPct().setScale(2).toPlainString());
        }
    }

    /** A rate as the form submits it: a percent of 0.00 or more with up to two decimals, and an
     *  optional from-date (absent: from the beginning of time) and to-date (absent: for good), not
     *  before the from-date. */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String pct, LocalDate from, LocalDate to) {
        @AssertTrue(message = "to-date before from-date")
        public boolean isToNotBeforeFrom() {
            return from == null || to == null || !to.isBefore(from);
        }
    }

    private final RateService service;
    private final HistoryService history;

    public RateController(RateService service, HistoryService history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<Rate> rates = asOf == null ? service.list(sheetId) : history.asOf(sheetId, asOf).rates();
        return rates.stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm body) {
        return RateView.of(service.set(sheetId, user.trim(), grade, body.from(), body.to(), new BigDecimal(body.pct())));
    }
}
