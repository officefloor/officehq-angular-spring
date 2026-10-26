package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.settings.ViewerPasses;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/rates")
public class RateController {

    /**
     * A grade's rate as the screen shows it; the percent always carries two decimals, and the from-date is null for a
     * rate that applies from the beginning of time; the to-date is null for a rate with no end.
     */
    public record RateView(String grade, String pct, LocalDate from, LocalDate to) {
        static RateView of(Rate r) {
            return new RateView(r.getGrade(), r.getPct().toPlainString(), r.getFrom(), r.getTo());
        }
    }

    /**
     * A rate as the form submits it: a percent of 0.00 or more with up to two decimals, an optional from-date and an
     * optional to-date not before it.
     */
    public record RateForm(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal pct,
            LocalDate from, LocalDate to) {

        @AssertTrue(message = "The to-date must not be before the from-date.")
        public boolean isToNotBeforeFrom() {
            return to == null || from == null || !to.isBefore(from);
        }
    }

    /** A candidate percent to preview: 0.00 or more with up to two decimals. */
    public record PreviewForm(@NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal pct) {
    }

    /** How many claims would show a different due, and the sum of the differences, with two decimals. */
    public record PreviewView(int count, String diff) {
    }

    private final RateService service;
    private final ClaimService claims;

    public RateController(RateService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    /** The rates set on the sheet, by grade and from-date; a grade with no rate is left out. */
    @GetMapping
    public List<RateView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(RateView::of).toList();
    }

    @PutMapping("/{grade}")
    public RateView set(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody RateForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return RateView.of(service.set(user, sheetId, grade, form.from(), form.to(), form.pct()));
    }

    /** Previews the grade's rate at a candidate percent on every date; nothing is saved or audited, so a viewer may too. */
    @ViewerPasses
    @PostMapping("/{grade}/preview")
    public PreviewView preview(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade, @Valid @RequestBody PreviewForm form) {
        ClaimService.RatePreview p = claims.previewRate(sheetId, grade, form.pct());
        return new PreviewView(p.count(), p.diff().toPlainString());
    }
}
