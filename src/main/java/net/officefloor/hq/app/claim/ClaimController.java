package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The claims people make on a sheet. */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /** The amount is a number with up to two decimals, negative for a correction, which names by number the
     * settled claim it corrects; no date means the sheet's today. */
    public record ClaimForm(
            @NotNull @Pattern(regexp = "-?\\d{1,10}(\\.\\d{1,2})?") String amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Positive Integer corrects) {}

    /** The amount is shown with two decimals; a claim has no number until it is opened, and no fixed due
     * until it is settled; it shows who opened it, if anyone did, and whether it is bound to the figure it had
     * at its person's last statement (none when that was n/a); a correction shows the number it corrects. */
    public record ClaimView(Long id, Long personId, String amount, LocalDate date, String note, ClaimStatus status,
            Integer number, String due, String openedBy, boolean stated, String statedDue,
            Integer corrects) {

        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(),
                    claim.getAmount().setScale(2).toPlainString(), claim.getDate(), claim.getNote(), claim.getStatus(),
                    claim.getNumber(),
                    claim.getSettledDue() != null ? claim.getSettledDue().setScale(2).toPlainString() : null,
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getStatedDue() != null ? claim.getStatedDue().setScale(2).toPlainString() : null,
                    claim.getCorrects());
        }
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(@RequestHeader("X-User") String user, @PathVariable String sheetId) {
        return service.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long personId, @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.add(user, sheetId, personId, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(@RequestHeader("X-User") String user, @PathVariable String sheetId,
            @PathVariable Long id, @Valid @RequestBody ClaimForm form) {
        return ClaimView.of(service.update(user, sheetId, id, new BigDecimal(form.amount()), form.date(), form.note(),
                form.corrects()));
    }

    @PostMapping("/claims/{id}/open")
    public ClaimView open(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.OPEN));
    }

    @PostMapping("/claims/{id}/settle")
    public ClaimView settle(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.SETTLED));
    }

    @PostMapping("/claims/{id}/void")
    public ClaimView voidClaim(@RequestHeader("X-User") String user, @PathVariable String sheetId, @PathVariable Long id) {
        return ClaimView.of(service.changeStatus(user, sheetId, id, ClaimStatus.VOID));
    }
}
