package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OpeningService {

    /** A pasted line, read: the person and period it names and its amount. */
    private record Line(Person person, Period period, BigDecimal amount) {
    }

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;

    public OpeningService(PeriodRepository periods, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, Audit audit) {
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    /** Add each pasted line of name,period,amount to the person's opening balance for the period, in
     *  order: the name is a person's name or alias on the sheet, the period one of its periods by name,
     *  the amount 0.00 or more with at most two decimals. Every line is read first; when any is refused,
     *  nothing is changed and nothing is written. Blank lines are skipped; a paste with no lines is
     *  refused. Returns how many lines went through. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        sheets.load(sheetId);
        sheetRows.findForUpdate(sheetId);
        List<Period> sheetPeriods = periods.findBySheetIdOrderByFromAsc(sheetId);
        List<Line> lines = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\\r?\\n")) {
            number++;
            if (raw.isBlank()) continue;
            lines.add(read(sheetId, sheetPeriods, raw, number));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Paste at least one line.");
        }
        for (Line line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + line.period().getName() + " amount=" + line.amount().setScale(2).toPlainString());
        }
        periods.saveAllAndFlush(sheetPeriods);
        return lines.size();
    }

    /** The sheet's periods in date order. */
    @Transactional(readOnly = true)
    public List<Period> periods(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** A line of name,period,amount. */
    private Line read(String sheetId, List<Period> sheetPeriods, String raw, int number) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 3) throw refused(number, "it needs a name, a period and an amount");
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null
                : people.findByNameOrAlias(sheetId, Person.keyOf(name)).orElse(null);
        if (person == null) throw refused(number, "no one on the sheet is named '" + name + "'");
        String periodKey = Period.keyOf(fields[1]);
        Period period = sheetPeriods.stream().filter((p) -> p.getNameKey().equals(periodKey)).findFirst()
                .orElseThrow(() -> refused(number, "the sheet has no period named '" + fields[1].trim() + "'"));
        BigDecimal amount;
        try {
            amount = new BigDecimal(fields[2].trim());
        } catch (NumberFormatException e) {
            throw refused(number, "the amount is not a number");
        }
        if (amount.scale() > 2 || amount.precision() - amount.scale() > 10) {
            throw refused(number, "the amount has more than two decimals or ten digits");
        }
        if (amount.signum() < 0) throw refused(number, "the amount is below 0.00");
        return new Line(person, period, amount);
    }

    private static ResponseStatusException refused(int number, String why) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Line " + number + ": " + why + ".");
    }
}
