package net.officefloor.hq.app.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sheet endpoints. The signed-in user's name is sent in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /** The clerk limit and floor are sent as text with exactly two decimals; the clerk limit null for no limit. */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor) {
        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString());
        }
    }

    /**
     * The clerk limit is 0 or more with at most two decimals; null or blank for no limit. The floor is
     * 0 or more with at most two decimals; null leaves it as it is.
     */
    public record SettingsRequest(@Pattern(regexp = "\\s*|\\d{1,10}(\\.\\d{1,2})?") String clerkLimit,
            @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String floor) {
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
        return SheetView.of(service.setSettings(id.trim(), user.trim(), limit.isEmpty() ? null : new BigDecimal(limit), floor));
    }
}
