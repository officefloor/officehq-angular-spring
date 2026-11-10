package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The opening balance box: lines of {@code name,period,amount}, taken in order. The name is a person
 * on the sheet by name or alias, the period one on the sheet (both compared without regard to case),
 * the amount 0.00 or more with two decimals. Every line is checked before anything is set; then each
 * adds its amount to the person's opening balance for the period, which is added to their carried
 * figure into it. The paste is one change, so a refused line sets nothing and writes nothing. Blank
 * lines are skipped; a paste with no lines is refused.
 */
@Service
public class OpeningImportService {

    /** An amount of 0.00 or more, with two decimals. */
    private static final String AMOUNT = "\\d{1,9}\\.\\d{2}";

    private record Line(Person person, Period period, BigDecimal amount) {
    }

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RoleService roles;
    private final Audit audit;

    public OpeningImportService(PeriodRepository periods, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, RoleService roles, Audit audit) {
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.roles = roles;
        this.audit = audit;
    }

    /** Adds every line's amount in order: how many lines went through. */
    @Transactional
    public int importOpenings(String sheetId, String user, String text) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        // hold the sheet so two pastes at the same moment add one after the other
        sheetRows.findLockedById(sheetId);
        List<Line> lines = new ArrayList<>();
        String[] raw = text.split("\\r?\\n|\\r");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(parse(sheetId, i + 1, raw[i]));
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,period,amount.");
        }
        for (Line line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            periods.saveAndFlush(line.period());
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + line.period().getName() + " amount=" + line.amount().toPlainString());
        }
        return lines.size();
    }

    private Line parse(String sheetId, int number, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length != 3) {
            throw refused("Line " + number + " needs a name, a period and an amount.");
        }
        String name = fields[0].trim();
        Person person = name.isEmpty() ? null : people.findBySheetIdAndNameOrAlias(sheetId, name).orElse(null);
        if (person == null) {
            throw refused("Line " + number + ": no one named \"" + name + "\" is on this sheet.");
        }
        String periodName = fields[1].trim();
        Period period = periodName.isEmpty() ? null
                : periods.findBySheetIdAndNameIgnoreCase(sheetId, periodName).orElse(null);
        if (period == null) {
            throw refused("Line " + number + ": no period named \"" + periodName + "\" is on this sheet.");
        }
        String amount = fields[2].trim();
        if (!amount.matches(AMOUNT)) {
            throw refused("Line " + number + ": enter an amount of 0.00 or more, with two decimals.");
        }
        return new Line(person, period, new BigDecimal(amount));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
