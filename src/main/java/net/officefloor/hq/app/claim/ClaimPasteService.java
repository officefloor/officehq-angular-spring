package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The paste box: lines of {@code name,amount,date,note}, each a claim for the person of that
 * name or alias (compared without regard to case), taken in order. Each claim arrives OPEN: it is
 * added and then opened by the user exactly as the open button would, so any refusal the open button
 * gives refuses the whole paste. A line whose person, date and note match one of that person's claims
 * as they stood before the paste (not VOID or DELETED) updates it instead: the newest matching DRAFT
 * is edited to the line's amount (when that changes it) and opened; a line whose matches include no
 * DRAFT refuses the paste. The date may be blank (the sheet's today)
 * and the note may be absent or hold commas. A note ending in {@code " #<reference>"} gives the claim
 * that external reference, which is not part of the note; a reference repeated within the paste, or
 * already used on the sheet, refuses the whole paste. Every line is held to exactly the claim form's rules;
 * the paste is one change, so a refused line adds nothing and writes nothing. Blank lines are
 * skipped; a paste with no lines is refused.
 */
@Service
public class ClaimPasteService {

    /** The longest note the claim form takes. */
    private static final int NOTE_MAX = 1000;

    /** The longest reference the claim form takes. */
    private static final int REF_MAX = 120;

    private record Line(Person person, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    /** A claim as it stood before the paste, for matching lines against. */
    private record Before(Long id, Long personId, LocalDate date, String note, boolean draft, BigDecimal amount) {
    }

    private final PersonRepository people;
    private final ClaimService claims;
    private final RoleService roles;
    private final SheetService sheets;

    public ClaimPasteService(PersonRepository people, ClaimService claims, RoleService roles, SheetService sheets) {
        this.sheets = sheets;
        this.roles = roles;
        this.people = people;
        this.claims = claims;
    }

    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        roles.refuseViewer(sheetId, user);
        List<Line> lines = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        String[] raw = text.split("\\r?\\n|\\r");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                Line line = parse(sheetId, i + 1, raw[i]);
                if (line.ref() != null && !refs.add(line.ref().toLowerCase(Locale.ROOT))) {
                    throw refused("Line " + (i + 1) + ": the reference " + line.ref() + " is already in this paste.");
                }
                lines.add(line);
                lineNumbers.add(i + 1);
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,amount,date,note.");
        }
        // matches are taken against the claims as they stood before the paste
        List<Before> before = claims.list(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .map(c -> new Before(c.getId(), c.getPersonId(), c.getDate(), c.getNote() == null ? "" : c.getNote(),
                        c.getStatus() == ClaimStatus.DRAFT, c.getAmount()))
                .toList();
        LocalDate today = sheets.entryDate(sheetId);
        List<Claim> done = new ArrayList<>();
        for (Line line : lines) {
            LocalDate dated = line.date() != null ? line.date() : today;
            List<Before> matches = before.stream()
                    .filter(c -> c.personId().equals(line.person().getId()) && dated.equals(c.date())
                            && line.note().equals(c.note()))
                    .toList();
            long id;
            if (matches.isEmpty()) {
                id = claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(), line.note(), null,
                        line.ref(), List.of()).getId();
            } else {
                Before draft = matches.stream().filter(Before::draft).max(Comparator.comparing(Before::id))
                        .orElseThrow(() -> refused("Line " + lineNumbers.get(done.size())
                                + ": it matches a claim that is no longer a DRAFT."));
                id = draft.id();
                if (draft.amount().compareTo(line.amount()) != 0) {
                    claims.updateAmount(sheetId, user, id, line.amount());
                }
            }
            done.add(claims.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
        }
        return done;
    }

    private Line parse(String sheetId, int number, String text) {
        String[] fields = text.split(",", 4);
        if (fields.length < 2) {
            throw refused("Line " + number + " needs at least a name and an amount.");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findBySheetIdAndNameOrAlias(sheetId, name).orElse(null);
        if (person == null) {
            throw refused("Line " + number + ": no one named \"" + name + "\" is on this sheet.");
        }
        String amount = fields[1].trim();
        if (!amount.matches(ClaimController.SIGNED_AMOUNT)) {
            throw refused("Line " + number + ": enter an amount with up to two decimals.");
        }
        LocalDate date = null;
        String dateText = fields.length > 2 ? fields[2].trim() : "";
        if (!dateText.isEmpty()) {
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                throw refused("Line " + number + ": enter the date as YYYY-MM-DD, or leave it blank.");
            }
        }
        String note = fields.length > 3 ? fields[3].trim() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
            ref = note.substring(hash + 2).trim();
            note = note.substring(0, hash).trim();
        }
        if (note.length() > NOTE_MAX) {
            throw refused("Line " + number + ": the note is too long.");
        }
        if (ref != null && ref.length() > REF_MAX) {
            throw refused("Line " + number + ": the reference is too long.");
        }
        return new Line(person, ClaimController.amount(amount), date, note, ref);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
