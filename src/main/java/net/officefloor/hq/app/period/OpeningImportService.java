package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pasted opening balances: lines of name,period,amount, the name a person's on the sheet (by name or
 * alias, as names are compared), the period one on the sheet by name, the amount two decimals, 0.00 or
 * more. Each line adds its amount to the person's opening balance for the period, which is part of
 * their carried figure for it. Every line is checked first; one that is refused refuses them all.
 * Blank lines are skipped; a paste with no line is refused.
 */
@Service
public class OpeningImportService {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}\\.\\d{2}");

    private record Line(Person person, Period period, BigDecimal amount) {}

    /** How many lines were taken, and the periods they changed, in the order first changed. */
    public record Result(int lines, List<Period> periods) {}

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

    @Transactional
    public Result importOpenings(String sheetId, String user, String text) {
        // Lock the sheet so two pastes at once add up rather than one overwriting the other.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Map<String, Period> byName = new LinkedHashMap<>();
        for (Period p : periods.findBySheetIdOrderByFromDateAsc(sheetId)) byName.put(p.getName(), p);
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            String[] fields = raw.split(",", -1);
            if (fields.length != 3) throw new OpeningImportRefusedException();
            String name = fields[0].strip();
            Period period = byName.get(fields[1].strip());
            String amount = fields[2].strip();
            if (name.isEmpty() || period == null || !AMOUNT.matcher(amount).matches()) {
                throw new OpeningImportRefusedException();
            }
            Person person = people.findByNameOrAlias(sheetId, name).orElseThrow(OpeningImportRefusedException::new);
            lines.add(new Line(person, period, new BigDecimal(amount)));
        }
        if (lines.isEmpty()) throw new OpeningImportRefusedException();
        Map<Long, Period> changed = new LinkedHashMap<>();
        for (Line line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            changed.put(line.period().getId(), line.period());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user.strip()
                    + " name=" + line.person().getName() + " period=" + line.period().getName()
                    + " amount=" + line.amount().toPlainString());
        }
        List<Period> saved = periods.saveAllAndFlush(changed.values());
        return new Result(lines.size(), saved);
    }
}
