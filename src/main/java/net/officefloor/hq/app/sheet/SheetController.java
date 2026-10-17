package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sheet endpoints. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /**
     * The clerk limit and floor are sent as text with exactly two decimals; the clerk limit null for no
     * limit. The terms are a number of days; the late
     * percent is sent with exactly two decimals. The policy date is null when none is set (the
     * sheet's today is used); so is the period numbering from when it is off.
     */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor, int terms,
            String latePct, LocalDate policyDate, LocalDate periodNumberingFrom) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms(), sheet.getLatePct().toPlainString(),
                    sheet.getPolicyDate(), sheet.getPeriodNumberingFrom());
        }
    }

    /**
     * The clerk limit is 0 or more with at most two decimals; null or blank for no limit. The floor is
     * 0 or more with at most two decimals; null leaves it as it is. The terms are a whole number of
     * days, 0 to 9999; null leaves them as they are. The late percent is 0 or more, below 1000, with at
     * most two decimals; null leaves it as it is. The policy date is yyyy-mm-dd, or blank for none (the
     * sheet's today); null leaves it as it is. The period numbering from is the same, blank for off.
     */
    public record SettingsRequest(@Pattern(regexp = "\\s*|\\d{1,10}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String floor,
            @Pattern(regexp = "\\s*\\d{1,4}\\s*") String terms,
            @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String latePct,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String policyDate,
            @Pattern(regexp = "\\s*(\\d{4}-\\d{2}-\\d{2})?\\s*") String periodNumberingFrom) {
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return SheetView.of(service.load(id.trim(), user.trim()));
    }

    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated TodayRequest request) {
        return SheetView.of(service.setToday(id.trim(), user.trim(), request.today()));
    }

    @PutMapping("/{id}/settings")
    public SheetView setSettings(@PathVariable @NotBlank @Size(max = 100) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Validated SettingsRequest request) {
        String limit = request.clerkLimit() == null ? "" : request.clerkLimit().trim();
        BigDecimal floor = request.floor() == null ? null : new BigDecimal(request.floor().trim());
        Integer terms = request.terms() == null ? null : Integer.valueOf(request.terms().trim());
        BigDecimal latePct = request.latePct() == null ? null : new BigDecimal(request.latePct().trim());
        Optional<LocalDate> policyDate = request.policyDate() == null ? null : dateSetting(request.policyDate().trim());
        Optional<LocalDate> periodNumberingFrom = request.periodNumberingFrom() == null ? null
                : dateSetting(request.periodNumberingFrom().trim());
        return SheetView.of(service.setSettings(id.trim(), user.trim(), limit.isEmpty() ? null : new BigDecimal(limit),
                floor, terms, latePct, policyDate, periodNumberingFrom));
    }

    /** A setting's date (the policy date, the period numbering from) as sent: empty when blank (none set); refused (400) when it is no real date. */
    private static Optional<LocalDate> dateSetting(String text) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such date: " + text);
        }
    }
}
