package net.officefloor.hq.app.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.officefloor.hq.app.claim.ClaimController;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reading and saving the sheet's settings. */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    public record GetSettingsRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user) {
    }

    /**
     * The settings to save; a setting left null is left as it is. A clerk limit: an amount with up
     * to two decimals, or blank for no limit. A floor: an amount with up to two decimals. Terms: a
     * whole number of days.
     */
    public record SetSettingsRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @Pattern(regexp = "\\s*|" + ClaimController.AMOUNT) String clerkLimit,
            @Pattern(regexp = ClaimController.AMOUNT) String floor,
            @Pattern(regexp = "\\s*\\d{1,4}\\s*") String terms) {
    }

    /** The settings in force; the clerk limit blank for none. */
    public record SettingsResponse(String clerkLimit, String floor, int terms) {
    }

    private static SettingsResponse response(Sheet sheet) {
        return new SettingsResponse(SettingService.text(sheet.getClerkLimit()), SettingService.text(sheet.getFloor()),
                sheet.getTerms());
    }

    private final SettingService service;

    public SettingController(SettingService service) {
        this.service = service;
    }

    @PostMapping("/get")
    public SettingsResponse get(@Valid @RequestBody GetSettingsRequest req) {
        return response(service.get(req.sheet().trim(), req.user().trim()));
    }

    @PostMapping("/set")
    public SettingsResponse set(@Valid @RequestBody SetSettingsRequest req) {
        String limit = req.clerkLimit() == null ? "" : req.clerkLimit().trim();
        return response(service.set(req.sheet().trim(), req.user().trim(), req.clerkLimit() != null,
                limit.isEmpty() ? null : ClaimController.amount(limit),
                req.floor() == null ? null : ClaimController.amount(req.floor()),
                req.terms() == null ? null : Integer.valueOf(req.terms().trim())));
    }
}
