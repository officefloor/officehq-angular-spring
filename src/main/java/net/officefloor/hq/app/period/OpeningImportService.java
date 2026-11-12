package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds opening balances per pasted line of name,period,amount, in order: the person on the sheet by name or alias,
 * the period on the sheet by name, and an amount of two decimals, 0.00 or more, added to the person's opening balance
 * for the period and so to their carried figure for it. Every line is checked first; if any is refused, nothing is
 * added.
 */
@Service
public class OpeningImportService {

    private static final String AMOUNT = "\\d{1,12}\\.\\d{2}";

    private record Line(int number, Person person, Period period, BigDecimal amount) {
    }

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final PersonService people;
    private final Audit audit;

    public OpeningImportService(PeriodRepository periods, SheetRepository sheets, PersonService people, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.people = people;
        this.audit = audit;
    }

    /** How many lines were added. */
    @Transactional
    public int importOpenings(String sheetId, String user, String text) {
        // Lock the sheet so no period closes while the openings are added.
        if (sheets.lockById(sheetId) == 0) {
            throw new SheetNotFoundException(sheetId);
        }
        List<Line> lines = new ArrayList<>();
        String[] rows = text.split("\\r?\\n");
        for (int i = 0; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                lines.add(parse(sheetId, i + 1, rows[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new OpeningImportException("Paste at least one line of name,period,amount");
        }
        for (Line line : lines) {
            Period period = line.period();
            period.addOpening(line.person().getId(), line.amount());
            periods.save(period);
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + period.getName() + " amount=" + line.amount().toPlainString());
        }
        periods.flush();
        return lines.size();
    }

    private Line parse(String sheetId, int number, String row) {
        String[] fields = row.split(",", -1);
        if (fields.length != 3) {
            throw new OpeningImportException(number, "expected name,period,amount");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw new OpeningImportException(number, "the name is blank");
        }
        Person person = people.findByNameOrAlias(sheetId, name)
                .orElseThrow(() -> new OpeningImportException(number, "no one on the sheet is named " + name));
        String periodName = fields[1].trim();
        Period period = periods.findBySheetIdAndName(sheetId, periodName)
                .orElseThrow(() -> new OpeningImportException(number, "the sheet has no period " + periodName));
        String amount = fields[2].trim();
        if (!amount.matches(AMOUNT)) {
            throw new OpeningImportException(number, "the amount is not 0.00 or more with two decimals");
        }
        return new Line(number, person, period, new BigDecimal(amount));
    }
}
