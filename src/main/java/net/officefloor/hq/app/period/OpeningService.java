package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The opening balances import: lines of name,period,amount each add the amount to the named person's
 * (by name or alias) opening balance for the named period on the sheet, which adds to their carried
 * figure for it. The amount has two decimals and is 0.00 or more. Every line is checked first; it is one
 * change, so when any line is refused nothing is added and nothing is recorded.
 */
@Service
public class OpeningService {

    /** An opening amount: two decimals, 0.00 or more. */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}\\.\\d{2}");

    /** The most lines one import takes. */
    static final int LINES_MAX = 1000;

    /** A line read from the import, checked. */
    private record Line(Person person, Period period, BigDecimal amount) {
    }

    private final PersonRepository people;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final Audit audit;

    public OpeningService(PersonRepository people, PeriodRepository periods, SheetService sheets, Audit audit) {
        this.people = people;
        this.periods = periods;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** Add each line's amount to its person's opening balance for its period, in order; how many lines went through. */
    @Transactional
    public int importOpenings(String sheetId, String user, String text) {
        // Take the sheet's lock first, so no person or period changes while the lines are checked and kept.
        sheets.lock(sheetId);
        List<Line> lines = new ArrayList<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(line(sheetId, i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,period,amount.");
        }
        if (lines.size() > LINES_MAX) {
            throw refused("Paste at most " + LINES_MAX + " lines at once.");
        }
        for (Line line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            periods.saveAndFlush(line.period());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + line.period().getName() + " amount=" + line.amount().toPlainString());
        }
        return lines.size();
    }

    private Line line(String sheetId, int number, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length != 3) {
            throw refused("Line " + number + " needs a name, a period and an amount.");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused("Line " + number + " has no name.");
        }
        Person person = people.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> refused("Line " + number + ": " + name + " is not on this sheet."));
        String periodName = fields[1].trim();
        Period period = periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(periodName))
                .orElseThrow(() -> refused("Line " + number + ": " + periodName + " is not a period on this sheet."));
        String amount = fields[2].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused("Line " + number + ": the amount must have two decimals and be 0.00 or more.");
        }
        return new Line(person, period, new BigDecimal(amount));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
