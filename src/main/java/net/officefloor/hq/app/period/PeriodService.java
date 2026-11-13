package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The periods on a sheet: named date ranges, no two overlapping. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final PersonRepository people;
    private final SettingService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, ClaimRepository claims,
            ClaimService claimService, PersonRepository people, SettingService settings, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.claims = claims;
        this.claimService = claimService;
        this.people = people;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's periods, the earliest first. */
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period from and to a date, inclusive. Refused when another period on the sheet has the
     *  name, the from-date is after the to-date, or it shares a day with another period. The sheet
     *  is held locked so periods added at the same moment are checked against each other. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        if (from.isAfter(to)) {
            throw new PeriodRefusedException("The period's from-date is after its to-date");
        }
        if (periods.nameInUse(sheetId, name)) {
            throw new PeriodRefusedException("The name " + name + " is already in use on the sheet");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new PeriodRefusedException("The period overlaps another on the sheet");
        }
        Period saved = periods.saveAndFlush(new Period(sheetId, name, from, to));
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + name
                + " from=" + from + " to=" + to);
        return saved;
    }

    /** Close a period, fixing its due-total: the figures shown on the claims that count dated in it
     *  (a claim with no figure adds nothing; a held person's OPEN claims count for nothing). A period already closed is refused. */
    @Transactional
    public Period close(String sheetId, String user, String name) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (period.isClosed()) {
            throw new PeriodClosedException("The period " + name + " is already closed");
        }
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !claim.countsLive(person.isHeld()) || !period.contains(claim.getDate())) {
                continue;
            }
            BigDecimal due = claimService.dueOf(sheetId, person, claim);
            if (due != null) {
                total = total.add(due);
            }
        }
        period.close(total);
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + name
                + " total=" + total.toPlainString());
        return saved;
    }

    /** Reopen a closed period, making its due-total live again. Only a steward may; a period that
     *  is not closed is refused. */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        sheets.lock(sheetId);
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new NoSuchElementException("No period " + name + " on sheet " + sheetId));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new PeriodRefusedException("Only a steward may reopen the period " + name);
        }
        if (!period.isClosed()) {
            throw new PeriodRefusedException("The period " + name + " is not closed");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + name);
        return saved;
    }
}
