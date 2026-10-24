package net.officefloor.hq.app.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The plans on a sheet, and the button that generates their claims. The signed-in user's name travels in
 * the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/plans")
@Validated
public class PlanController {

    /** A plan: its id, its person, its amount (as text with two decimals), start, interval in days (null
     * for a monthly plan), day of the month (null for one every N days), end, and how many claims it has
     * made so far. */
    public record PlanView(long id, long personId, String amount, LocalDate start, Integer every, Integer day,
            LocalDate end, int generated) {
        static PlanView of(Plan p) {
            return new PlanView(p.getId(), p.getPersonId(),
                    p.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), p.getStart(), p.getEvery(),
                    p.getDay(), p.getEnd(), p.getGenerated());
        }
    }

    /** A plan asked: its person, an amount (up to two decimals), a start and end (YYYY-MM-DD) and either an
     * interval in whole days or a day of the month (the other blank or absent). */
    public record PlanRequest(
            long personId,
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String start,
            @Pattern(regexp = "\\s*(\\d{1,5})?\\s*") String every,
            @Pattern(regexp = "\\s*(\\d{1,2})?\\s*") String day,
            @NotNull @Pattern(regexp = "\\s*\\d{4}-\\d{2}-\\d{2}\\s*") String end) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2);
        }

        Integer everyValue() {
            return whole(every);
        }

        Integer dayValue() {
            return whole(day);
        }

        private static Integer whole(String text) {
            return text == null || text.isBlank() ? null : Integer.valueOf(text.trim());
        }
    }

    /** How many claims a generate made, the claims, and every plan on the sheet as it stands after. */
    public record GeneratedView(int made, List<ClaimView> claims, List<PlanView> plans) {
    }

    private final PlanService service;
    private final ClaimService claims;

    public PlanController(PlanService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    @GetMapping
    public List<PlanView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.plans(sheetId.trim()).stream().map(PlanView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PlanRequest request) {
        return PlanView.of(service.add(sheetId.trim(), user.trim(), request.personId(), request.amountValue(),
                date(request.start()), request.everyValue(), request.dayValue(), date(request.end())));
    }

    @PostMapping("/generate")
    public GeneratedView generate(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        String sheet = sheetId.trim();
        List<ClaimView> made = ClaimView.allOf(service.generate(sheet, user.trim()), claims);
        return new GeneratedView(made.size(), made, list(sheet));
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new PlanDatesException();
        }
    }
}
