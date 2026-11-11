package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The sheet's settings. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/settings")
@Validated
public class SettingController {

    /** The settings as shown: the clerk limit with two decimals, null when there is none; the floor with two decimals. */
    public record SettingsView(String clerkLimit, String floor) {

        static SettingsView of(Sheet sheet) {
            return new SettingsView(sheet.getClerkLimit() == null ? null
                    : sheet.getClerkLimit().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    sheet.getFloor().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    /** A clerk limit of 0.00 or more with up to two decimals; blank or missing means no limit. */
    public record SettingsRequest(@Pattern(regexp = "\\s*(\\d{1,10}(\\.\\d{1,2})?)?\\s*") String clerkLimit) {

        BigDecimal clerkLimitValue() {
            return clerkLimit == null || clerkLimit.isBlank() ? null
                    : new BigDecimal(clerkLimit.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** A floor of 0.00 or more with up to two decimals. */
    public record FloorRequest(@NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String floor) {

        BigDecimal floorValue() {
            return new BigDecimal(floor.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView get(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return SettingsView.of(service.get(sheetId.trim()));
    }

    @PutMapping
    public SettingsView set(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid SettingsRequest request) {
        return SettingsView.of(service.setClerkLimit(sheetId.trim(), user.trim(), request.clerkLimitValue()));
    }

    @PutMapping("/floor")
    public SettingsView setFloor(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid FloorRequest request) {
        return SettingsView.of(service.setFloor(sheetId.trim(), user.trim(), request.floorValue()));
    }
}
