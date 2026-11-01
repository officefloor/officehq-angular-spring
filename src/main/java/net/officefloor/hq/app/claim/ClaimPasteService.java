package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pasted claims: lines of name,amount,date,note (the note may hold commas and may be absent; a blank
 * date is the entry date while set, else the sheet's today), each a DRAFT claim for the person of the name on the sheet (ignoring
 * case and surrounding spaces), added in order exactly as the claim form adds one. A note ending in
 * " #<reference>" gives the claim that external reference (that part is not the note); a reference already
 * on the sheet, or given twice in the paste, compared without regard to case, refuses it. Blank lines are
 * skipped; a paste with no line is refused. It is one change: a line that is refused refuses them all.
 * <p>
 * Each claim then arrives OPEN: opened by the user exactly as the open button opens one, in paste order,
 * any refusal of the open refusing the paste. A line whose person, date and note match one of that
 * person's claims as they stood before the paste (neither VOID nor DELETED) updates that claim instead:
 * the newest such DRAFT (not already taken by an earlier line) is edited to the line's amount, when it
 * differs, and opened; a line whose matches include no such DRAFT refuses the paste.
 */
@Service
public class ClaimPasteService {

    /** The claim form's amount: up to two decimals, negative only for a correction (which a paste cannot name). */
    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,12}(\\.\\d{1,2})?");
    private static final int NOTE_MAX = 500;
    private static final int REF_MAX = 200;
    /** A note ending in a space, a hash and the reference. */
    private static final Pattern NOTE_REF = Pattern.compile("(.*) #(.+)", Pattern.DOTALL);

    private record Line(Long personId, BigDecimal amount, LocalDate date, String note, String ref) {}

    private final ClaimService claims;
    private final ClaimRepository stored;
    private final PersonRepository people;

    public ClaimPasteService(ClaimService claims, ClaimRepository stored, PersonRepository people) {
        this.claims = claims;
        this.stored = stored;
        this.people = people;
    }

    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            Line line = parse(sheetId, raw);
            if (line.ref() != null && !refs.add(Claim.refKeyOf(line.ref()))) throw new DuplicateClaimRefException();
            lines.add(line);
        }
        if (lines.isEmpty()) throw new ClaimPasteRefusedException();
        // The claims as they stood before the paste, newest first: a line never matches one it added.
        List<Claim> before = stored.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .toList();
        Set<Long> taken = new HashSet<>();
        List<Claim> opened = new ArrayList<>();
        for (Line line : lines) {
            LocalDate on = claims.dateOrEntryDate(sheetId, line.date());
            List<Claim> matches = before.stream()
                    .filter(c -> c.getPersonId().equals(line.personId()) && on.equals(c.getDate())
                            && Objects.toString(c.getNote(), "").equals(line.note()))
                    .toList();
            Long id;
            if (matches.isEmpty()) {
                id = claims.add(sheetId, user, line.personId(), line.amount(), line.date(), line.note(), null,
                        line.ref()).getId();
            } else {
                Claim draft = matches.stream()
                        .filter(c -> c.getStatus() == ClaimStatus.DRAFT && !taken.contains(c.getId()))
                        .findFirst().orElseThrow(ClaimPasteRefusedException::new);
                taken.add(draft.getId());
                id = draft.getId();
                if (draft.getAmount().compareTo(line.amount()) != 0) {
                    claims.edit(sheetId, user, id, line.amount(), draft.getDate(), draft.getNote(),
                            draft.getCorrects(), draft.getRef(), draft.getTags());
                }
            }
            opened.add(claims.changeStatus(sheetId, user, id, ClaimStatus.OPEN));
        }
        return opened;
    }

    private Line parse(String sheetId, String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 3) throw new ClaimPasteRefusedException();
        String name = fields[0].strip();
        String amount = fields[1].strip();
        String date = fields[2].strip();
        String note = fields.length == 4 ? fields[3].strip() : "";
        String ref = null;
        Matcher tail = NOTE_REF.matcher(note);
        if (tail.matches() && !tail.group(2).isBlank()) {
            note = tail.group(1).strip();
            ref = tail.group(2).strip();
        }
        if (name.isEmpty() || !AMOUNT.matcher(amount).matches() || note.length() > NOTE_MAX
                || (ref != null && ref.length() > REF_MAX)) {
            throw new ClaimPasteRefusedException();
        }
        Person person = people.findByNameOrAlias(sheetId, name)
                .orElseThrow(ClaimPasteRefusedException::new);
        LocalDate on;
        try {
            on = date.isEmpty() ? null : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new ClaimPasteRefusedException();
        }
        return new Line(person.getId(), new BigDecimal(amount).setScale(2), on, note, ref);
    }
}
