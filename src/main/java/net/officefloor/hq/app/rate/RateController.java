package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Grade;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /** What the screen shows of a rate; the percent always has two decimals. No from-date means the
     *  rate applies from the beginning of time; no to-date, until the grade's next rate. Level is how many
     *  parents up the rate's sheet is: 0 for the sheet's own, 1 for its parent's, and so on. */
    public record RateView(Grade grade, String pct, LocalDate from, LocalDate to, int level) {
        static RateView of(Rate rate) {
            return of(0, rate);
        }

        static RateView of(int level, Rate rate) {
            return new RateView(rate.getGrade(), rate.getPct().toPlainString(), rate.getFrom(), rate.getTo(), level);
        }
    }

    /** A rate's percent, from-date and to-date, as entered on the form; no from-date means from the
     *  beginning of time, no to-date until the grade's next rate. */
    public record RateForm(@NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal pct,
            LocalDate from, LocalDate to) {
    }

    /** What a candidate rate would do: how many claims would show a different due, and the sum of
     *  the differences with two decimals. */
    public record PreviewView(int count, String diff) {
    }

    /** The rate import box's text: lines of grade,rate,from,to. */
    public record ImportForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many rates the import set, and the rates, in the order of their lines. */
    public record ImportView(int set, List<RateView> rates) {
    }

    private final RateService service;
    private final ImportRatesService importer;
    private final ClaimService claims;

    public RateController(RateService service, ImportRatesService importer, ClaimService claims) {
        this.service = service;
        this.importer = importer;
        this.claims = claims;
    }

    /** Set a rate per line of grade,rate,from,to, in order, all or none. */
    @PostMapping("/import")
    public ImportView importRates(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid ImportForm form) {
        List<RateView> set = importer.importRates(sheetId.trim(), SignedInUser.name(user), form.text())
                .stream().map(RateView::of).toList();
        return new ImportView(set.size(), set);
    }

    /** Preview a candidate percent for a grade on every date against the sheet's OPEN and PENDING
     *  claims; nothing is saved and nothing is audited. */
    @GetMapping("/{grade}/preview")
    public PreviewView preview(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable Grade grade,
            @RequestParam @NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal pct) {
        ClaimService.RatePreview preview = claims.previewRate(sheetId.trim(), grade, pct);
        return new PreviewView(preview.count(), preview.diff().toPlainString());
    }

    /** The sheet's rates in grade order, then by from-date, followed by its parents' up to three levels up,
     *  nearest first; a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.listInherited(sheetId.trim()).stream().map((r) -> RateView.of(r.level(), r.rate())).toList();
    }

    /** Set the rate for a grade from a from-date through a to-date, replacing the one it had for that from-date. */
    @PutMapping("/{grade}")
    public RateView set(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable Grade grade,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid RateForm form) {
        return RateView.of(service.set(sheetId.trim(), SignedInUser.name(user), grade, form.from(), form.to(), form.pct()));
    }
}
