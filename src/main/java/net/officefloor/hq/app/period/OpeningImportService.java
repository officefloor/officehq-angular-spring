package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Paste opening balances: lines of {@code name,period,amount}, a person on the sheet by name or alias (as the
 * claim paste matches them), a period on the sheet by name (case ignored) and an amount of 0.00 or more with up
 * to two decimals. Every line is checked first, and the whole paste is one change: a refused line adds nothing.
 * Otherwise each line adds its amount to the person's carried figure for the period, in order, whether or not
 * the period is closed (a closed period keeps showing the figure fixed when it was closed).
 */
@Service
public class OpeningImportService {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");

    private record Line(Person person, Period period, BigDecimal amount) {
    }

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final ApplicationEventPublisher events;

    public OpeningImportService(PeriodRepository periods, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, ApplicationEventPublisher events) {
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.events = events;
    }

    /** Add an opening balance per line, in order; returns how many lines went through. */
    @Transactional
    public int importOpenings(String sheetId, String user, String text) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        List<Period> all = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            lines.add(parse(sheetId, all, raw));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paste at least one line");
        }
        for (Line line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            events.publishEvent(new OpeningSet(sheetId, user, line.person().getName(), line.period().getName(),
                    line.amount()));
        }
        periods.flush();
        return lines.size();
    }

    private Line parse(String sheetId, List<Period> all, String raw) {
        String[] f = raw.split(",", -1);
        if (f.length != 3) throw refused(raw, "give a name, a period and an amount");
        String name = f[0].trim();
        Person person = name.isEmpty() ? null
                : people.findBySheetIdAndNameOrAlias(sheetId, name).stream().findFirst().orElse(null);
        if (person == null) throw refused(raw, "no one named \"" + name + "\" is on the sheet");
        String periodName = f[1].trim();
        Period period = all.stream().filter(p -> p.getName().equalsIgnoreCase(periodName)).findFirst()
                .orElseThrow(() -> refused(raw, "no period named \"" + periodName + "\" is on the sheet"));
        String amount = f[2].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused(raw, "the amount must be 0.00 or more, with up to two decimals");
        }
        return new Line(person, period, new BigDecimal(amount));
    }

    private static ResponseStatusException refused(String line, String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Line \"" + line + "\": " + why);
    }
}
