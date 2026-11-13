package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.person.Patron;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.Statement;
import net.officefloor.hq.app.person.Watch;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.role.Delegation;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet as it stood at the end of an as-of date, for a user's screen while they view it so: its rows as
 * the changes made while the sheet's today was on or before that date left them, a later change not
 * counting, and the date standing in for the sheet's today. Each list is in the order the screen reads the
 * live one in.
 */
@Component
@Transactional(readOnly = true)
public class AsOf {

    /** The header a screen viewing the sheet as of a date (YYYY-MM-DD) sends with each request. */
    public static final String HEADER = "X-As-Of";

    private final History history;
    private final SheetRepository sheets;

    public AsOf(History history, SheetRepository sheets) {
        this.history = history;
        this.sheets = sheets;
    }

    /** The sheet as of the date, its today the date, its parent the one it has now; as it was started when no change to it counts by then. */
    public Sheet sheet(String sheetId, LocalDate asOf) {
        Sheet now = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        Sheet then = history.findAsOf(Sheet.class, sheetId, sheetId, asOf)
                .orElseGet(() -> new Sheet(sheetId, now.getCreatedBy(), now.getCreatedAt(), asOf));
        then.setToday(asOf);
        // the parent is not dated: the sheet names the one it names now
        then.setParent(now.getParent());
        return then;
    }

    /** The sheet's today as of the date: the date itself. */
    public LocalDate today(String sheetId, LocalDate asOf) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        return asOf;
    }

    /** The people, in list order: by their position (those kept before positions, by id). */
    public List<Person> people(String sheetId, LocalDate asOf) {
        return sorted(Person.class, sheetId, asOf, Comparator.comparing((Person p) -> p.getPosition() == null
                ? p.getId() : p.getPosition()).thenComparing(Person::getId));
    }

    /** The claims as listed: newest first. */
    public List<Claim> claims(String sheetId, LocalDate asOf) {
        return sorted(Claim.class, sheetId, asOf, Comparator.comparingLong(Claim::getListOrder).reversed());
    }

    /** The rates, by grade. */
    public List<Rate> rates(String sheetId, LocalDate asOf) {
        return sorted(Rate.class, sheetId, asOf, Comparator.comparing(Rate::getGrade));
    }

    /** An ancestor's rates, by grade, as they stood when the sheet last changed while its today was on or
     *  before the date: the ancestor's own today does not date them, the sheet's does. */
    public List<Rate> inheritedRates(String sheetId, String ancestor, LocalDate asOf) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        return history.listAsOfSheet(Rate.class, ancestor, sheetId, asOf).stream()
                .sorted(Comparator.comparing(Rate::getGrade)).toList();
    }

    /** The periods, by from-date. */
    public List<Period> periods(String sheetId, LocalDate asOf) {
        return sorted(Period.class, sheetId, asOf, Comparator.comparing(Period::getFromDate));
    }

    /** The statements, in the order they were taken. */
    public List<Statement> statements(String sheetId, LocalDate asOf) {
        return sorted(Statement.class, sheetId, asOf, Comparator.comparing(Statement::getId));
    }

    /** The patrons named, in the order they were named. */
    public List<Patron> patrons(String sheetId, LocalDate asOf) {
        return sorted(Patron.class, sheetId, asOf, Comparator.comparing(Patron::getId));
    }

    /** The watches kept, in the order they were started. */
    public List<Watch> watches(String sheetId, LocalDate asOf) {
        return sorted(Watch.class, sheetId, asOf, Comparator.comparing(Watch::getId));
    }

    /** The roles given, by user name. */
    public List<SheetRole> roles(String sheetId, LocalDate asOf) {
        return sorted(SheetRole.class, sheetId, asOf, Comparator.comparing(SheetRole::getUserName));
    }

    /** The delegations, in the order they were made. */
    public List<Delegation> delegations(String sheetId, LocalDate asOf) {
        return sorted(Delegation.class, sheetId, asOf, Comparator.comparing(Delegation::getId));
    }

    private <T> List<T> sorted(Class<T> type, String sheetId, LocalDate asOf, Comparator<T> order) {
        sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        return history.listAsOf(type, sheetId, asOf).stream().sorted(order).toList();
    }
}
