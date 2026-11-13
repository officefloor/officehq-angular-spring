package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.role.ActionNotAllowedException;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paste box: lines of name,amount,date,note (the note may hold commas and may be absent; a blank date
 * takes the sheet's entry date, its today when off), each a DRAFT claim for the person on the sheet with
 * the name or an alias (ignoring case), added in order. A note ending in " #<reference>" gives the claim that external
 * reference, which is not part of the note. Blank lines are skipped. One change: every line is checked
 * with the claim form's rules (a reference used twice in the paste too, ignoring case), and a refused line
 * refuses the paste, adding nothing and writing nothing.
 * <p>
 * Each line's claim is then opened by the user as the open button would, in paste order; any refusal the
 * open would give refuses the whole paste. A line naming a person, date and note matching one of their
 * claims as they stood before the paste (void and deleted ones aside) updates that claim instead: the
 * newest matching DRAFT takes the line's amount and is opened; matches with no DRAFT refuse the paste.
 */
@Service
public class PasteService {

    private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,13}(\\.\\d{1,2})?");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final PersonRepository people;
    private final ClaimService claims;
    private final ClaimRepository claimsRepo;
    private final SheetService sheets;

    public PasteService(PersonRepository people, ClaimService claims, ClaimRepository claimsRepo,
            SheetService sheets) {
        this.people = people;
        this.claims = claims;
        this.claimsRepo = claimsRepo;
        this.sheets = sheets;
    }

    private record Line(int number, Person person, BigDecimal amount, LocalDate date, String note,
            String ref) {
    }

    /** The claims added, in the order of their lines. */
    @Transactional
    public List<Claim> paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                Line line = parse(sheetId, i + 1, raw[i]);
                if (line.ref() != null && !refs.add(line.ref().toLowerCase(Locale.ROOT))) {
                    throw refused(line.number(), "the reference " + line.ref() + " is already in this paste.");
                }
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new ClaimPasteException("Paste at least one line of name,amount,date,note.");
        }
        List<Claim> before = claimsRepo.findBySheetIdOrderByListOrderDesc(sheetId);
        LocalDate entry = sheets.entryDefault(sheetId);
        List<Optional<Claim>> matched = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (Line line : lines) {
            Optional<Claim> match = match(before, line, line.date() != null ? line.date() : entry);
            if (match.isPresent() && !taken.add(match.get().getId())) {
                throw refused(line.number(), "its claim is already updated by an earlier line.");
            }
            matched.add(match);
        }
        List<Claim> done = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            try {
                Claim claim;
                if (matched.get(i).isPresent()) {
                    claim = matched.get(i).get();
                    if (claim.getAmount().compareTo(line.amount()) != 0) {
                        claims.update(sheetId, user, claim.getId(), line.amount(), claim.getDate(), claim.getNote(),
                                claim.getCorrects(), line.ref() != null ? line.ref() : claim.getRef(),
                                List.copyOf(claim.getTags()));
                    }
                } else {
                    claim = claims.add(sheetId, user, line.person().getId(), line.amount(), line.date(),
                            line.note(), null, line.ref(), List.of());
                }
                done.add(claims.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN));
            } catch (PeriodClosedException | PersonRetiredException | ClaimCorrectionException
                    | ClaimRefException | ClaimStatusException | ClaimNotDraftException | ClaimLockedException
                    | ClaimLineException | ClaimNoteException | ClaimCapException | ActionNotAllowedException e) {
                throw new ClaimPasteException("Line " + line.number() + ": " + e.getMessage());
            }
        }
        return done;
    }

    /** The newest DRAFT of the line's person with its date and note, among the claims before the paste;
     *  none when nothing matches. A line matching only claims that are not DRAFTs is refused. */
    private static Optional<Claim> match(List<Claim> before, Line line, LocalDate date) {
        List<Claim> matches = before.stream()
                .filter(c -> c.getPersonId().equals(line.person().getId()))
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .filter(c -> date.equals(c.getDate()))
                .filter(c -> line.note().equals(c.getNote() == null ? "" : c.getNote().trim()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.stream().filter(c -> c.getStatus() == ClaimStatus.DRAFT).findFirst()
                .orElseThrow(() -> refused(line.number(), "its matching claim is no longer a DRAFT.")));
    }

    private Line parse(String sheetId, int number, String raw) {
        String[] f = raw.split(",", 4);
        String name = f[0].trim();
        if (name.isEmpty() || name.length() > 200) {
            throw refused(number, "enter a name.");
        }
        Person person = people.findByNameOrAlias(sheetId, name)
                .orElseThrow(() -> refused(number, "no one on the sheet is named " + name + "."));
        String amount = f.length > 1 ? f[1].trim() : "";
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused(number, "enter an amount with up to two decimals.");
        }
        String date = f.length > 2 ? f[2].trim() : "";
        LocalDate on = null;
        if (!date.isEmpty()) {
            try {
                on = LocalDate.parse(date, DATE);
            } catch (DateTimeParseException e) {
                throw refused(number, "enter a date as YYYY-MM-DD.");
            }
        }
        String note = f.length > 3 ? f[3].trim() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
            ref = note.substring(hash + 2).trim();
            note = note.substring(0, hash).trim();
        }
        if (note.length() > 1000) {
            throw refused(number, "the note is too long.");
        }
        if (ref != null && ref.length() > 200) {
            throw refused(number, "the reference is too long.");
        }
        return new Line(number, person, new BigDecimal(amount), on, note, ref);
    }

    private static ClaimPasteException refused(int number, String why) {
        return new ClaimPasteException("Line " + number + ": " + why);
    }
}
