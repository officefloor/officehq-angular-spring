package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimNotFoundException;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads back the audit file for a sheet: it is not a second record, only a view of what the file
 * holds.
 */
@Service
public class AuditViewService {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetParentRepository parents;
    private final ClaimRepository claims;

    public AuditViewService(Audit audit, PersonRepository people, SheetParentRepository parents,
            ClaimRepository claims) {
        this.audit = audit;
        this.people = people;
        this.parents = parents;
        this.claims = claims;
    }

    /**
     * A claim's timeline: the sheet's audit lines about it exactly as the file holds them, newest first.
     * For a claim with a number those carrying number=<its number>; for one without, those without a
     * number that name its person, amount and date together.
     */
    @Transactional(readOnly = true)
    public List<String> claimLines(String sheetId, Long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        Pattern numbered = Pattern.compile(" number=[^ ]*(?= [^ =]+=|$)");
        Pattern about;
        if (claim.getNumber() != null) {
            about = Pattern.compile(" number=" + Pattern.quote(claim.getNumber()) + "(?= [^ =]+=|$)");
        } else {
            String name = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse(null);
            if (name == null) return List.of();
            about = Pattern.compile(" person=" + Pattern.quote(name)
                    + " amount=" + Pattern.quote(claim.getAmount().setScale(2).toPlainString())
                    + " date=" + Pattern.quote(claim.getDate().toString()) + "(?= [^ =]+=|$)");
        }
        List<String> out = linesOf(audit.lines(), sheetId, about);
        if (claim.getNumber() == null) out.removeIf(line -> numbered.matcher(line).find());
        return out;
    }

    /**
     * The sheet's audit lines exactly as the file holds them, newest first. With a name (blank: none)
     * only those carrying name=<the name> or person=<the name>, the name compared without surrounding
     * spaces and ignoring case, followed by the matching lines of the sheet's descendants (each
     * sheet's newest first, the sheets in order of id); when the name, or an alias, is of a person transferred here, they are
     * followed by the lines about them from the sheet they came from, up to the one of their transfer
     * here, newest first.
     */
    @Transactional(readOnly = true)
    public List<String> lines(String sheetId, String name) {
        String wanted = name == null ? "" : name.strip();
        Pattern about = wanted.isEmpty() ? null
                : Pattern.compile(" (?:name|person)=" + Pattern.quote(wanted) + "(?= [^ =]+=|$)",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<String> all = audit.lines();
        List<String> out = linesOf(all, sheetId, about);
        if (about == null) return out;
        List<String> descendants = new ArrayList<>(parents.descendantsOf(sheetId));
        Collections.sort(descendants);
        for (String descendant : descendants) out.addAll(linesOf(all, descendant, about));
        String origin = people.findByNameOrAlias(sheetId, wanted).map(Person::getTransferredFrom).orElse(null);
        if (origin == null || origin.equals(sheetId)) return out;
        List<String> theirs = linesOf(all, origin, about);
        String transfer = "PERSON_TRANSFERRED sheet=" + origin + " ";
        String toHere = " to=" + sheetId;
        for (int i = 0; i < theirs.size(); i++) {
            String line = theirs.get(i);
            if (line.startsWith(transfer) && (line.endsWith(toHere) || line.contains(toHere + " "))) {
                out.addAll(theirs.subList(i, theirs.size()));
                break;
            }
        }
        return out;
    }

    /** The sheet's lines, newest first; with a pattern only those it finds. */
    private static List<String> linesOf(List<String> all, String sheetId, Pattern about) {
        String sheetField = "sheet=" + sheetId;
        List<String> out = new ArrayList<>();
        for (String line : all) {
            String[] fields = line.split(" ", 3);
            if (fields.length < 2 || !fields[1].equals(sheetField)) continue;
            if (about != null && !about.matcher(line).find()) continue;
            out.add(line);
        }
        Collections.reverse(out);
        return out;
    }
}
