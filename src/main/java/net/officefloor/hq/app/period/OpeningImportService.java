package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds an opening balance per pasted line of {@code name,period,amount}, in order: the person on the
 * sheet by name or alias, a period on the sheet by name, and an amount of 0.00 or more with two
 * decimals, added to the person's opening balance for that period (and so to their carried figure into
 * it). Every line is checked before any is added: when any line is refused nothing is added, and nothing
 * is recorded. Blank lines are skipped. */
@Service
public class OpeningImportService {

    /** An amount of 0.00 or more with two decimals. */
    private static final Pattern AMOUNT = Pattern.compile("\\s*\\d{1,10}\\.\\d{2}\\s*");

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;

    public OpeningImportService(PeriodRepository periods, PersonRepository people, SheetRepository sheets, Audit audit) {
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    private record Line(Person person, Period period, BigDecimal amount) {
    }

    /** How many lines went through. */
    @Transactional
    public int importOpenings(String sheetId, String user, String text) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        List<Line> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length != 3 || fields[0].isBlank() || fields[1].isBlank()
                    || !AMOUNT.matcher(fields[2]).matches()) {
                throw new OpeningImportException();
            }
            Person person = people.findBySheetIdAndNameOrAlias(sheetId, fields[0])
                    .orElseThrow(OpeningImportException::new);
            Period period = periods.findBySheetIdAndNameKey(sheetId, Period.keyOf(fields[1]))
                    .orElseThrow(OpeningImportException::new);
            lines.add(new Line(person, period, new BigDecimal(fields[2].trim())));
        }
        if (lines.isEmpty()) throw new OpeningImportException();
        for (Line line : lines) {
            Period period = periods.lockByIdAndSheetId(line.period().getId(), sheetId)
                    .orElseThrow(PeriodNotFoundException::new);
            period.addOpening(line.person().getId(), line.amount());
            audit.change("OPENING_SET", sheetId, user, "name=" + line.person().getName(),
                    "period=" + period.getName(), "amount=" + line.amount().toPlainString());
        }
        periods.flush();
        return lines.size();
    }
}
