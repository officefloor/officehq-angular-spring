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
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
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
     *  (null when it applies from the beginning of time) and the to-date (null when it has no end). */
    public record RateView(String grade, String pct, LocalDate from, LocalDate to) {
        static RateView of(Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().setScale(2).toPlainString(), rate.getFromDate(),
                    rate.getToDate());
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
     *  {@link History}). */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        List<Rate> rates = asOf != null ? history.asOf(sheetId.trim(), asOf).rates() : service.list(sheetId.trim());
        return rates.stream().map(RateView::of).toList();
    }

    /** What the screen shows of a rate preview: the count and the sum of the differences as text with
     *  two decimals. */
    public record PreviewView(int count, String diff) {
    }

    /** Preview a candidate rate for a grade (see {@link RateService#preview}); nothing is saved. */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestParam @NotNull @Pattern(regexp = "\\s*\\d{1,5}(\\.\\d{1,2})?\\s*") String pct) {
        RateService.Preview preview = service.preview(sheetId.trim(), grade, new BigDecimal(pct.trim()).setScale(2));
        return new PreviewView(preview.count(), preview.diff().toPlainString());
    }

    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody RateForm form) {
        return RateView.of(service.set(sheetId.trim(), user.trim(), grade, form.pctValue(), form.from(),
                form.to()));
    }
}
