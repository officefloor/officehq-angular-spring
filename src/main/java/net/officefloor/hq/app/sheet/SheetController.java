package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /** A sheet as the screen shows it; the clerk limit (null: no limit) and floor and late percent have exactly two decimals; terms is in days;
     *  the policy date is null when it is the sheet's today; the period numbering from is null when it is off; the cap (two decimals) is null when there is none. */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor, int terms, String latePct,
            LocalDate policyDate, LocalDate periodNumberingFrom, Map<String, Integer> gradeTerms, String cap) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(), sheet.getLatePct().toPlainString(), sheet.getPolicyDate(),
                    sheet.getPeriodNumberingFrom(), sheet.getGradeTerms(),
                    sheet.getCap() == null ? null : sheet.getCap().toPlainString());
        }
    }

    public record TodayChange(@NotNull LocalDate today) {
    }

    /** The sheet's settings as the form submits them; a missing clerk limit means no limit. */
    public record SettingsChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal clerkLimit) {
    }

    /** The sheet's floor as the form submits it. */
    public record FloorChange(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal floor) {
    }

    /** The sheet's terms, in days, as the form submits them. */
    public record TermsChange(@NotNull @Min(0) @Max(36500) Integer terms) {
    }

    /** A grade's own terms, in days, as the form submits them; missing ones leave the grade on the sheet's terms. */
    public record GradeTermsChange(@Min(0) @Max(36500) Integer terms) {
    }

    /** The sheet's late percent as the form submits it. */
    public record LatePctChange(@NotNull @DecimalMin("0.00") @Digits(integer = 3, fraction = 2) BigDecimal latePct) {
    }

    /** The sheet's policy date as the form submits it; a missing one means the sheet's today. */
    public record PolicyDateChange(LocalDate policyDate) {
    }

    /** The sheet's period numbering from as the form submits it; a missing one means off. */
    public record PeriodNumberingFromChange(LocalDate periodNumberingFrom) {
    }

    /** The sheet's cap as the form submits it; a missing one means none. */
    public record CapChange(@DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal cap) {
    }

    private final SheetService sheets;

    public SheetController(SheetService sheets) {
        this.sheets = sheets;
    }

    /** Load (re-read) a sheet by id, starting it empty if it has never been loaded. */
    @PostMapping("/{id}/load")
    public SheetView load(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return SheetView.of(sheets.load(id, user));
    }

    /** Change the sheet's today (an ISO date). */
    @PutMapping("/{id}/today")
    public SheetView setToday(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TodayChange request) {
        return SheetView.of(sheets.setToday(id, user, request.today()));
    }

    /** Change the sheet's settings. */
    @PutMapping("/{id}/settings")
    public SheetView setSettings(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody SettingsChange request) {
        return SheetView.of(sheets.setClerkLimit(id, user, request.clerkLimit()));
    }

    /** Change the sheet's floor. */
    @PutMapping("/{id}/settings/floor")
    public SheetView setFloor(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody FloorChange request) {
        return SheetView.of(sheets.setFloor(id, user, request.floor()));
    }

    /** Change the sheet's terms. */
    @PutMapping("/{id}/settings/terms")
    public SheetView setTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody TermsChange request) {
        return SheetView.of(sheets.setTerms(id, user, request.terms()));
    }

    /** Change a grade's own terms. */
    @PutMapping("/{id}/settings/terms/{grade}")
    public SheetView setGradeTerms(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody GradeTermsChange request) {
        return SheetView.of(sheets.setGradeTerms(id, user, grade, request.terms()));
    }

    /** Change the sheet's late percent. */
    @PutMapping("/{id}/settings/late-pct")
    public SheetView setLatePct(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody LatePctChange request) {
        return SheetView.of(sheets.setLatePct(id, user, request.latePct()));
    }

    /** Change the sheet's policy date. */
    @PutMapping("/{id}/settings/policy-date")
    public SheetView setPolicyDate(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PolicyDateChange request) {
        return SheetView.of(sheets.setPolicyDate(id, user, request.policyDate()));
    }

    /** Change the sheet's period numbering from. */
    @PutMapping("/{id}/settings/period-numbering-from")
    public SheetView setPeriodNumberingFrom(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PeriodNumberingFromChange request) {
        return SheetView.of(sheets.setPeriodNumberingFrom(id, user, request.periodNumberingFrom()));
    }

    /** Change the sheet's cap. */
    @PutMapping("/{id}/settings/cap")
    public SheetView setCap(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CapChange request) {
        return SheetView.of(sheets.setCap(id, user, request.cap()));
    }
}
