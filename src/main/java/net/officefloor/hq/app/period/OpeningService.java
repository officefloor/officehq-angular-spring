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

/**
 * The opening balances paste: lines of name,period,amount, each adding its amount to the opening balance of
 * the person on the sheet with the name or an alias (ignoring case) for the period on the sheet with the name
 * (ignoring case), and so to their carried figure for it. The amount has up to two decimals and is 0.00 or more.
 * Blank lines are skipped. Every line is checked first; a refused line refuses the paste, adding nothing and
 * writing nothing.
 */
@Service
public class OpeningService {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;

    public OpeningService(PeriodRepository periods, PersonRepository people, SheetRepository sheets, Audit audit) {
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    private record Line(Person person, Period period, BigDecimal amount) {
    }

    /** Paste opening balances: how many lines went through. */
    @Transactional
    public int paste(String sheetId, String user, String text) {
        // Locking the sheet makes two users' pastes, and a close, go one after the other.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        List<Period> sheetPeriods = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        List<Line> lines = new ArrayList<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(parse(sheetId, sheetPeriods, i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new PeriodRefusedException("Paste at least one line of name,period,amount.");
        }
        for (Line line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + line.period().getName() + " amount=" + line.amount().toPlainString());
        }
        lines.stream().map(Line::period).distinct().forEach(periods::saveAndFlush);
        return lines.size();
    }

    private Line parse(String sheetId, List<Period> sheetPeriods, int number, String raw) {
        String[] f = raw.split(",", -1);
        if (f.length != 3) {
            throw refused(number, "enter name,period,amount.");
        }
        String name = f[0].trim();
        if (name.isEmpty() || name.length() > 200) {
            throw refused(number, "enter a name.");
        }
        Person person = people.findByNameOrAlias(sheetId, name)
                .orElseThrow(() -> refused(number, "no one on the sheet is named " + name + "."));
        String periodName = f[1].trim();
        Period period = sheetPeriods.stream().filter(p -> p.getName().equalsIgnoreCase(periodName)).findFirst()
                .orElseThrow(() -> refused(number, "no period on the sheet is named " + periodName + "."));
        String amount = f[2].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused(number, "enter an amount of 0.00 or more with up to two decimals.");
        }
        return new Line(person, period, new BigDecimal(amount).setScale(2));
    }

    private static PeriodRefusedException refused(int number, String why) {
        return new PeriodRefusedException("Line " + number + ": " + why);
    }
}
