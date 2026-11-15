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
import org.springframework.web.bind.annotation.PostMapping;
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
     *  from-date (null when it applies from the beginning of time), to-date (null: for good) and how
     *  many versions it has (the one shown, its newest, being in force); for a forward-only newest
     *  version the highest claim id there was when it was saved (only claims added after it apply
     *  it), and the earlier versions claims added before it apply instead, newest first. */
    public record RateView(String grade, LocalDate from, LocalDate to, String pct, int version, Long afterClaim,
            List<EarlierView> earlier) {
        static RateView of(Rate r) {
            return new RateView(r.getGrade(), r.getFrom(), r.getTo(), r.getPct().setScale(2).toPlainString(), r.getVersion(),
                    r.getAfterClaim(), r.getEarlier().stream().map(EarlierView::of).toList());
        }
    }

    /** An earlier version of a rate as a claim added before its later forward-only one applies it. */
    public record EarlierView(String pct, LocalDate to, Long afterClaim) {
        static EarlierView of(Rate.Earlier e) {
            return new EarlierView(e.pct().setScale(2).toPlainString(), e.to(), e.afterClaim());
        }
    }

    /** A rate as the form submits it: a percent of 0.00 or more with up to two decimals, and an
     *  optional from-date (absent: from the beginning of time) and to-date (absent: for good), not
     *  before the from-date. */
    public record RateForm(@NotNull @Pattern(regexp = "\\d{1,7}(\\.\\d{1,2})?") String pct, LocalDate from, LocalDate to) {
        @AssertTrue(message = "to-date before from-date")
        public boolean isToNotBeforeFrom() {
            return from == null || to == null || !to.isBefore(from);
        }
    }

    /** The rate import box's text: lines of grade,rate,from,to. */
    public record ImportForm(@NotNull @Size(max = 1_000_000) String text) {
    }

    private final RateService service;
    private final HistoryService history;
    private final RateChain chain;

    public RateController(RateService service, HistoryService history, RateChain chain) {
        this.service = service;
        this.chain = chain;
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

    /** The rates of each of the sheet's ancestors, its parent's first (shown as of a date, each
     *  as it stood at the end of that date): a grade with no rate of its own in force on a date
     *  takes the first of them that has one. */
    @GetMapping("/inherited")
    public List<List<RateView>> inherited(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        List<List<Rate>> inherited = asOf == null ? chain.inherited(sheetId)
                : chain.ancestors(sheetId).stream().map(a -> history.asOf(a, asOf).rates()).toList();
        return inherited.stream().map(l -> l.stream().map(RateView::of).toList()).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm body) {
        return RateView.of(service.set(sheetId, user.trim(), grade, body.from(), body.to(), new BigDecimal(body.pct())));
    }

    /** The rates set by an import, in the order of their lines. */
    @PostMapping("/import")
    public List<RateView> importRates(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ImportForm body) {
        return service.importRates(sheetId, user.trim(), body.text()).stream().map(RateView::of).toList();
    }
}
