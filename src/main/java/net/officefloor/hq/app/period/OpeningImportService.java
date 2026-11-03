package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The opening balances box: lines of name,period,amount, each adding the amount to the named
 *  person's carried figure for the period (their opening balance for it). */
@Service
public class OpeningImportService {

    /** An opening amount: 0.00 or more, with exactly two decimals. */
    private static final Pattern AMOUNT = Pattern.compile("\\s*\\d{1,12}\\.\\d{2}\\s*");

    /** One parsed line: the person, the period and the amount. */
    private record Line(Person person, Period period, BigDecimal amount) {
    }

    private final PersonRepository people;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final History history;
    private final Audit audit;

    public OpeningImportService(PersonRepository people, PeriodRepository periods, SheetService sheets,
            History history, Audit audit) {
        this.people = people;
        this.periods = periods;
        this.sheets = sheets;
        this.history = history;
        this.audit = audit;
    }

    /** What an import came to: how many lines went through, and the periods they changed. */
    public record Imported(int added, List<Period> periods) {
    }

    /** Add each line's amount to its person's opening balance for its period, in order, checking
     *  every line first; one change, so when any line is refused nothing is changed and nothing is
     *  recorded. A person is named by name or alias and a period by name, without regard to case.
     *  Blank lines are skipped; an import with no lines is refused. */
    @Transactional
    public Imported importOpenings(String sheetId, String user, String text) {
        sheets.load(sheetId, user);
        List<Period> onSheet = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        List<Line> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            if (!raw.isBlank()) {
                lines.add(parse(sheetId, onSheet, n, raw));
            }
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        Map<Long, Period> changed = new LinkedHashMap<>();
        for (Line line : lines) {
            Period period = changed.computeIfAbsent(line.period().getId(), id -> periods.lockByIdAndSheetId(id, sheetId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet")));
            period.addOpening(line.person().getId(), line.amount());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + period.getName() + " amount=" + line.amount().toPlainString());
        }
        List<Period> saved = periods.saveAllAndFlush(changed.values());
        history.record(sheetId);
        return new Imported(lines.size(), saved);
    }

    /** A line of name,period,amount. */
    private Line parse(String sheetId, List<Period> onSheet, int n, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 3) {
            throw refused(n, "expected name,period,amount");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findByNameOrAlias(sheetId, name).orElse(null);
        if (person == null) {
            throw refused(n, "no one named \"" + name + "\" on this sheet");
        }
        String periodName = fields[1].trim();
        Period period = onSheet.stream().filter(p -> p.getName().equalsIgnoreCase(periodName)).findFirst()
                .orElseThrow(() -> refused(n, "no period named \"" + periodName + "\" on this sheet"));
        if (!AMOUNT.matcher(fields[2]).matches()) {
            throw refused(n, "the amount must be 0.00 or more, with two decimals");
        }
        return new Line(person, period, new BigDecimal(fields[2].trim()));
    }

    private static ResponseStatusException refused(int n, String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": " + why);
    }
}
