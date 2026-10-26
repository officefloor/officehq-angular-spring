package net.officefloor.hq.app.period;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/periods/openings")
@Validated
public class OpeningController {

    /** The paste box's text: lines of name,period,amount. */
    public record OpeningForm(@NotNull @Size(max = 200_000) String text) {
    }

    /** How many lines went through, and the sheet's periods as they now stand, in date order. */
    public record OpeningView(int added, List<PeriodView> periods) {
    }

    private final OpeningService service;

    public OpeningController(OpeningService service) {
        this.service = service;
    }

    /** Add each line's amount to its person's opening balance for its period, all or none. */
    @PostMapping
    public OpeningView paste(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid OpeningForm form) {
        String sheet = sheetId.trim();
        int added = service.paste(sheet, SignedInUser.name(user), form.text());
        return new OpeningView(added, service.periods(sheet).stream().map(PeriodView::of).toList());
    }
}
