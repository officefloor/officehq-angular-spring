package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds and opens a claim per pasted line of name,amount,date,note, in order, each opened by the user exactly as the
 * open button would (numbered in paste order); any refusal the open button would give refuses the whole paste. A line
 * whose person, date and note match one of that person's claims as they stood before the paste (never a claim another
 * line of the paste added, nor a void or deleted one) updates that claim instead: the newest matching DRAFT not taken
 * by an earlier line is edited to the line's amount and opened; a line whose matches include no such DRAFT is refused.
 * The name must be on the sheet (compared as The name must be on the sheet (compared as
 * names are); the amount, date and note are taken as the claim form takes them (a blank date is the sheet's entry date while set, else its today, the
 * note may hold commas or be absent). A note ending in " #" and a reference gives the claim that external reference
 * (not part of its note), checked as the claim form's is, so a reference used twice in the paste is refused too. The
 * whole is one change: if any line is refused, nothing is added.
 */
@Service
public class PasteClaimsService {

    /** The amount as the claim form takes it. */
    private static final String AMOUNT = "\\s*-?\\d{1,10}(\\.\\d{1,2})?\\s*";

    private static final int MAX_NOTE = 1000;

    private static final int MAX_REF = 255;

    private record Line(int number, Long personId, BigDecimal amount, LocalDate date, String note, String ref) {
    }

    private final PersonService persons;
    private final ClaimService claims;
    private final ClaimRepository repository;
    private final SheetService sheets;

    public PasteClaimsService(PersonService persons, ClaimService claims, ClaimRepository repository,
            SheetService sheets) {
        this.persons = persons;
        this.claims = claims;
        this.repository = repository;
        this.sheets = sheets;
    }

    /** How many lines went through: claims added or updated, each opened. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] rows = text.split("\\r?\\n");
        for (int i = 0; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                lines.add(parse(sheetId, i + 1, rows[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new PasteClaimsException("Paste at least one line of name,amount,date,note");
        }
        // Each line's match is found among the claims as they stood before the paste.
        LocalDate entryDate = sheets.entryDate(sheetId);
        Set<Long> taken = new HashSet<>();
        List<Long> matched = new ArrayList<>();
        for (Line line : lines) {
            matched.add(match(sheetId, line, line.date() != null ? line.date() : entryDate, taken));
        }
        // Each claim is checked as the claim form and the open button check it; a refused one rolls back the paste.
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Long id = matched.get(i);
            Claim claim = id != null ? claims.editAmount(sheetId, user, id, line.amount())
                    : claims.add(sheetId, user, line.personId(), line.amount(), line.date(), line.note(), null,
                            line.ref());
            claims.changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN);
        }
        return lines.size();
    }

    /** The DRAFT the line updates; null when nothing matches it; refused when what matches holds no free DRAFT. */
    private Long match(String sheetId, Line line, LocalDate date, Set<Long> taken) {
        List<Claim> matches = repository.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, line.personId()).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .filter(c -> date.equals(c.getDate()))
                .filter(c -> line.note().trim().equals(c.getNote() == null ? "" : c.getNote().trim()))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        Optional<Claim> draft = matches.stream()
                .filter(c -> c.getStatus() == ClaimStatus.DRAFT && !taken.contains(c.getId()))
                .max(Comparator.comparing(Claim::getId));
        if (draft.isEmpty()) {
            throw new PasteClaimsException(line.number(), "it matches a claim that is no longer a DRAFT");
        }
        taken.add(draft.get().getId());
        return draft.get().getId();
    }

    private Line parse(String sheetId, int number, String row) {
        String[] fields = row.split(",", 4);
        if (fields.length < 3) {
            throw new PasteClaimsException(number, "expected name,amount,date,note");
        }
        Person person = persons.findByNameOrAlias(sheetId, fields[0])
                .orElseThrow(() -> new PasteClaimsException(number, "no one named " + fields[0].trim()));
        if (!fields[1].matches(AMOUNT)) {
            throw new PasteClaimsException(number, "the amount is not one with at most two decimals");
        }
        BigDecimal amount = new BigDecimal(fields[1].trim()).setScale(2, RoundingMode.UNNECESSARY);
        LocalDate date;
        try {
            date = fields[2].isBlank() ? null : LocalDate.parse(fields[2].trim());
        } catch (DateTimeParseException e) {
            throw new PasteClaimsException(number, "the date is not yyyy-mm-dd");
        }
        String note = fields.length > 3 ? fields[3].trim() : "";
        String ref = null;
        int hash = note.lastIndexOf(" #");
        if (hash >= 0 && !note.substring(hash + 2).isBlank()) {
            ref = note.substring(hash + 2).trim();
            note = note.substring(0, hash);
        }
        if (note.length() > MAX_NOTE) {
            throw new PasteClaimsException(number, "the note is too long");
        }
        if (ref != null && ref.length() > MAX_REF) {
            throw new PasteClaimsException(number, "the reference is too long");
        }
        return new Line(number, person.getId(), amount, date, note, ref);
    }
}
