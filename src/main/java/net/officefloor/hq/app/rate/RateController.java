package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/rates")
@Validated
public class RateController {

    /** What the screen shows of a rate; the percent as text with two decimals, the from-date
     *  (null when it applies from the beginning of time) and the to-date (null when it has no end);
     *  and the level it is set on: 0 for the sheet itself, 1 for its parent, 2 for the parent's
     *  parent, and so on; and how many versions it has had; where a forward-only rate was saved in
     *  the order claims are added (null: it applies to every claim, see Rate#appliesTo); and its
     *  older versions, the newest first. */
    public record RateView(String grade, String pct, LocalDate from, LocalDate to, int level, int version,
            Long forwardAfter, List<VersionView> older) {
        static RateView of(Rate rate, List<Rate> older) {
            return of(rate, 0, older);
        }

        static RateView of(Rate rate, int level, List<Rate> older) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2).toPlainString(), rate.getFromDate(),
                    rate.getToDate(), level, rate.getVersion(), rate.getForwardAfter(),
                    older.stream().map(VersionView::of).toList());
        }
    }

    /** An older version of a rate: its percent as text with two decimals, its to-date and where it
     *  was saved when it was forward-only (see {@link RateView}). */
    public record VersionView(String pct, LocalDate to, Long forwardAfter) {
        static VersionView of(Rate rate) {
            return new VersionView(rate.getPct().setScale(2).toPlainString(), rate.getToDate(),
                    rate.getForwardAfter());
        }
    }

    /** The rate form: a percent of 0.00 or more with up to two decimals, and an optional from-date
     *  (left out: from the beginning of time) and an optional to-date not before it (left out: no
     *  end). */
    public record RateForm(@NotNull @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct,
            LocalDate from, LocalDate to) {

        @AssertTrue
        public boolean isToNotBeforeFrom() {
            return from == null || to == null || !to.isBefore(from);
        }

        BigDecimal pctValue() {
            return new BigDecimal(pct.trim()).setScale(2);
        }
    }

    private final RateService service;
    private final History history;

    public RateController(RateService service, History history) {
        this.service = service;
        this.history = history;
    }

    /** The rates on the sheet; as of a day when one is given, as they stood at the end of it (see
     *  {@link History}); then the rates of its ancestors, nearest first, likewise as of that day when
     *  one is given (a grade with no rate in force on a date on the sheet takes theirs). */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        History.SheetAsOf then = asOf != null ? history.asOf(sheetId.trim(), asOf) : null;
        List<Rate> rates = then != null ? then.rates() : service.list(sheetId.trim());
        Function<Rate, List<Rate>> older = then != null ? then.basis()::olderVersions : service::olderVersions;
        List<RateView> views = new ArrayList<>(rates.stream().map(r -> RateView.of(r, older.apply(r))).toList());
        List<List<Rate>> inherited = then != null ? then.inherited() : service.inherited(sheetId.trim());
        for (int i = 0; i < inherited.size(); i++) {
            int level = i + 1;
            inherited.get(i).forEach(rate -> views.add(RateView.of(rate, level, older.apply(rate))));
        }
        return views;
    }

    /** What the screen shows of a rate preview: the count and the sum of the differences as text with
     *  two decimals. */
    public record PreviewView(int count, String diff) {
    }

    /** Preview a candidate rate for a grade (see {@link RateService#preview}); nothing is saved. */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestParam @NotNull @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct) {
        RateService.Preview preview = service.preview(sheetId.trim(), grade, new BigDecimal(pct.trim()).setScale(2));
        return new PreviewView(preview.count(), preview.diff().toPlainString());
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm form) {
        Rate saved = service.set(sheetId.trim(), user.trim(), grade, form.pctValue(), form.from(), form.to());
        return RateView.of(saved, service.olderVersions(saved));
    }

    /** Withdraw the newest version of a grade's rate for a from-date (left out: the rate from the
     *  beginning of time); the rate as it now stands, or no content when it is gone. */
    @DeleteMapping("/{grade}")
    public ResponseEntity<RateView> withdraw(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[A-Z]{1,3}") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return service.withdraw(sheetId.trim(), user.trim(), grade, from)
                .map(rate -> ResponseEntity.ok(RateView.of(rate, service.olderVersions(rate))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
