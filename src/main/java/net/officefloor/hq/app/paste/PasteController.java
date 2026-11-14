package net.officefloor.hq.app.paste;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimLockedException;
import net.officefloor.hq.app.claim.ClaimNotEditableException;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.CorrectionRefusedException;
import net.officefloor.hq.app.claim.LinedAmountException;
import net.officefloor.hq.app.claim.NoteRequiredException;
import net.officefloor.hq.app.claim.MaxOpenException;
import net.officefloor.hq.app.claim.PeriodCapException;
import net.officefloor.hq.app.claim.RefInUseException;
import net.officefloor.hq.app.claim.StatusChangeRefusedException;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.setting.PermissionRefusedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/claims/paste")
public class PasteController {

    /** The pasted lines of name,amount,date,note. */
    public record PasteRequest(@NotNull @Size(max = 1_000_000) String text) {
    }

    /** The claims added or updated, and opened, in the order of their lines. */
    public record PasteView(List<ClaimView> added) {
    }

    private final PasteService service;
    private final ClaimService claims;

    public PasteController(PasteService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    /** Add, or update a matching DRAFT, and open a claim per pasted line, or change nothing when any
     *  line, or any open, is refused. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PasteView paste(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PasteRequest request) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        return new PasteView(service.paste(sheet, by, request.text()).stream()
                .map(c -> ClaimView.of(c, claims.roundsBy(sheet, c), claims.logOf(c.getId()),
                        claims.commentsOf(c.getId()), claims.linesOf(c.getId())))
                .toList());
    }

    /** A line the claim form would refuse, dated in a closed period or after its person retired, or a
     *  claim the open button (or the editor, for a matched DRAFT) would refuse. */
    @ExceptionHandler({PasteRefusedException.class, PeriodClosedException.class, PersonRetiredException.class,
            NoteRequiredException.class, PeriodCapException.class, MaxOpenException.class, StatusChangeRefusedException.class,
            ClaimNotEditableException.class, ClaimLockedException.class, RefInUseException.class,
            LinedAmountException.class, CorrectionRefusedException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    /** Only a role the sheet's permission matrix allows opens a claim. */
    @ExceptionHandler(PermissionRefusedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
