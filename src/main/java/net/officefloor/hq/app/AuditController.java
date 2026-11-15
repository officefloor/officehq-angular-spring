package net.officefloor.hq.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The audit viewer: reads what the audit file holds for a sheet; it is not a second record. */
@RestController
@RequestMapping("/api/sheets")
@Validated
public class AuditController {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetService sheets;
    private final ClaimRepository claims;

    public AuditController(Audit audit, PersonRepository people, SheetService sheets, ClaimRepository claims) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
        this.claims = claims;
    }

    /** The sheet's audit lines, newest first; with a name, only those about that person, followed
     *  by those about them on each of the sheet's descendants (sheets by id, each newest first),
     *  then, when that person was transferred here, by the lines about them on the sheet they came from
     *  (newest first, as that sheet's file holds them). */
    @GetMapping("/{id}/audit")
    public List<String> lines(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestParam(defaultValue = "") @Size(max = 200) String name) {
        List<String> lines = new ArrayList<>(audit.lines(id, name));
        String who = name.trim();
        if (!who.isEmpty()) {
            sheets.descendants(id).forEach(d -> lines.addAll(audit.lines(d, who)));
            people.findBySheetIdAndNameIgnoreCase(id, who).stream()
                    .map(Person::getOriginSheetId).filter(Objects::nonNull).findFirst()
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, who)));
        }
        return lines;
    }

    /** The claim's timeline: the audit lines about it, newest first, as the file holds them — those
     *  carrying its number, or for a claim with no number those naming its person, amount and date
     *  together. Refused (404) when the claim is not on the sheet. */
    @GetMapping("/{id}/claims/{claimId}/timeline")
    public List<String> timeline(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String id,
            @PathVariable long claimId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        Claim claim = claims.findByIdAndSheetId(claimId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Person person = people.findById(claim.getPersonId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        return audit.claimLines(id, claim.getNumber(), person.getName(),
                claim.getAmount().setScale(2).toPlainString(), claim.getDate().toString());
    }
}
