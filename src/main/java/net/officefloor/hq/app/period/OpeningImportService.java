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
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The opening balance paste box: each pasted line adds an amount to a person's carried figure for a period. */
@Service
public class OpeningImportService {

    /** An opening amount: 0.00 or more, with exactly two decimals. */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,12}\\.\\d{2}");

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;

    public OpeningImportService(PeriodRepository periods, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, Audit audit) {
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
    }

    /** A pasted line read into an opening balance. */
    private record Line(Person person, Period period, BigDecimal amount) {
    }

    /**
     * Add an opening balance per line of {@code text} (name,period,amount), in order: the amount is added to the
     * person's opening balance for the period, and so to their carried figure for it. The person is the one on the
     * sheet whose name or an alias matches (trimmed, ignoring case); the period is one on the sheet by name. Every line
     * is checked first: when any line is refused, nothing is added and nothing is recorded. Blank lines are skipped; a
     * paste with no lines is refused. The periods changed, in the order first changed.
     */
    @Transactional
    public ImportResult importOpenings(String sheetId, String user, String text) {
        sheets.load(sheetId, user);
        // The sheet is locked so two pastes at the same moment both add their amounts.
        sheetStore.findLockedById(sheetId).orElseThrow();
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            if (!raw.isBlank()) {
                lines.add(read(sheetId, raw));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line");
        }
        Map<Long, Period> changed = new LinkedHashMap<>();
        for (Line line : lines) {
            Period period = changed.computeIfAbsent(line.period().getId(), id -> line.period());
            period.addOpening(line.person().getId(), line.amount());
            audit.recordChangeOnCommit(sheetId, user, "OPENING_SET sheet=" + sheetId + " by=" + user + " name="
                    + line.person().getName() + " period=" + period.getName() + " amount="
                    + line.amount().toPlainString());
        }
        List<Period> saved = periods.saveAllAndFlush(changed.values());
        return new ImportResult(lines.size(), saved);
    }

    /** What a paste did: how many lines went through, and the periods they changed. */
    public record ImportResult(int lines, List<Period> periods) {
    }

    private Line read(String sheetId, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length != 3) {
            throw refused("Each line is name,period,amount");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused("Each line needs a name");
        }
        Person person = people.findBySheetIdAndNameOrAliasIgnoreCase(sheetId, name)
                .orElseThrow(() -> refused("No one named " + name + " is on this sheet"));
        String periodName = fields[1].trim();
        Period period = periods.findBySheetIdAndName(sheetId, periodName)
                .orElseThrow(() -> refused("No period named " + periodName + " is on this sheet"));
        String amount = fields[2].trim();
        if (!AMOUNT.matcher(amount).matches()) {
            throw refused("An amount is 0.00 or more, with two decimals");
        }
        return new Line(person, period, new BigDecimal(amount));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
