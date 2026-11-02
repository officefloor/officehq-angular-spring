package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/settings")
@Validated
public class SettingsController {

    /** What the screen shows of a sheet's settings; the clerk limit as text with two decimals
     *  (null when there is no limit). */
    public record SettingsView(String clerkLimit) {
        static SettingsView of(BigDecimal clerkLimit) {
            return new SettingsView(clerkLimit == null ? null : clerkLimit.setScale(2).toPlainString());
        }
    }

    /** The clerk limit form: 0.00 or more with up to two decimals, or blank for no limit. */
    public record ClerkLimitForm(@Pattern(regexp = "\\s*(\\d{1,12}(\\.\\d{1,2})?)?\\s*") String value) {

        BigDecimal limitValue() {
            return value == null || value.isBlank() ? null : new BigDecimal(value.trim()).setScale(2);
        }
    }

    private final SheetService service;

    public SettingsController(SheetService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return SettingsView.of(service.clerkLimit(sheetId.trim()));
    }

    @PutMapping("/clerk-limit")
    public SettingsView setClerkLimit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClerkLimitForm form) {
        return SettingsView.of(service.setClerkLimit(sheetId.trim(), user.trim(), form.limitValue()).getClerkLimit());
    }
}
