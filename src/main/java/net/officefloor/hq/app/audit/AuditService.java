package net.officefloor.hq.app.audit;

import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimNotFoundException;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The audit lines a sheet's viewer shows, read from the audit file as it holds them. */
@Service
public class AuditService {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final ClaimRepository claims;

    public AuditService(Audit audit, PersonRepository people, SheetRepository sheets, ClaimRepository claims) {
        this.audit = audit;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
    }

    /**
     * The sheet's lines, newest first, or (with a name) only those about that person, followed by the
     * lines about them on each of the sheet's descendants (sheets by id, each newest first); when the
     * person so named on the sheet was transferred to it, followed by the lines about them from the
     * sheet they came from, newest first, as that sheet's lines hold them.
     */
    @Transactional(readOnly = true)
    public List<String> lines(String sheetId, String name) {
        List<String> lines = new ArrayList<>(audit.lines(sheetId, name));
        if (name != null && !name.isBlank()) {
            for (String descendant : descendantsOf(sheetId)) {
                lines.addAll(audit.lines(descendant, name));
            }
            people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                    .map(Person::getOriginSheetId)
                    .ifPresent(origin -> lines.addAll(audit.lines(origin, name)));
        }
        return lines;
    }

    /**
     * The claim's timeline: the sheet's lines about the claim, newest first — those carrying its number,
     * or for a claim with no number those naming its person, amount and date together.
     */
    @Transactional(readOnly = true)
    public List<String> claimLines(String sheetId, long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(ClaimNotFoundException::new);
        String number = claim.getNumber() == null || claim.getNumber().isBlank() ? null : claim.getNumber();
        return audit.claimLines(sheetId, number, person.getName(),
                claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                String.valueOf(claim.getDate()));
    }

    /** The sheet's activity: one line {@code <user> <EVENT> <count>} per user and event, by user then event. */
    public List<String> activity(String sheetId) {
        return audit.activity(sheetId);
    }

    /** The ids of the sheet's children, their children, and so on, in id order. */
    private Set<String> descendantsOf(String sheetId) {
        Set<String> found = new TreeSet<>();
        Deque<String> parents = new ArrayDeque<>();
        parents.add(sheetId);
        while (!parents.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(parents.poll())) {
                if (!child.getId().equals(sheetId) && found.add(child.getId())) parents.add(child.getId());
            }
        }
        return found;
    }
}
