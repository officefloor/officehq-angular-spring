package net.officefloor.hq.app.sheet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** Sheet endpoints. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets")
public class SheetController {

    /** A sheet as the screen shows it: its today, its clerk limit (two decimals; none for no limit) and
     *  its floor (two decimals) and its terms (days). */
    public record SheetView(String id, LocalDate today, String clerkLimit, String floor, int terms) {

        static SheetView of(Sheet sheet) {
            return new SheetView(sheet.getId(), sheet.getToday(),
                    sheet.getClerkLimit() == null ? null : sheet.getClerkLimit().toPlainString(),
                    sheet.getFloor().toPlainString(), sheet.getTerms());
        }
    }

    /** The sheet's settings; a missing clerk limit means no limit. */
    public record SettingsRequest(@DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal clerkLimit) {
    }

    /** The sheet's floor: the least a claim's live due can be. */
    public record FloorRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal floor) {
    }

    /** The sheet's terms: the number of days after a claim's date it is due by. */
    public record TermsRequest(@NotNull @Min(0) @Max(36500) Integer terms) {
    }

    public record TodayRequest(@NotNull LocalDate today) {
    }

    private final SheetService service;

    public SheetController(SheetService service) {
        this.service = service;
    }

    /** Load a sheet by id, starting it empty if it was never loaded before. */
    @PostMapping("/{id}/load")
    public SheetView load(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user) {
        return SheetView.of(service.load(id.trim(), user.trim()));
    }

    /** Change the sheet's today. */
    @PutMapping("/{id}/today")
    public SheetView setToday(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TodayRequest request) {
        return SheetView.of(service.setToday(id.trim(), user.trim(), request.today()));
    }

    /** Change the sheet's settings. */
    @PutMapping("/{id}/settings")
    public SheetView setSettings(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid SettingsRequest request) {
        return SheetView.of(service.setClerkLimit(id.trim(), user.trim(), request.clerkLimit()));
    }

    /** Change the sheet's floor. */
    @PutMapping("/{id}/settings/floor")
    public SheetView setFloor(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid FloorRequest request) {
        return SheetView.of(service.setFloor(id.trim(), user.trim(), request.floor()));
    }

    /** Change the sheet's terms. */
    @PutMapping("/{id}/settings/terms")
    public SheetView setTerms(@PathVariable @NotBlank @Size(max = 200) String id,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid TermsRequest request) {
        return SheetView.of(service.setTerms(id.trim(), user.trim(), request.terms()));
    }
}
