package net.officefloor.hq.app.rate;

import net.officefloor.hq.app.sheet.Sheet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The rate set for each grade on a sheet. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final Rollup rollup;
    private final RateChains chains;
    private final RateVersionRepository versions;
    private final ClaimRepository claims;
    private final RoleService roles;

    public RateService(RateRepository rates, SheetService sheets, Audit audit, Rollup rollup, RateChains chains,
            RateVersionRepository versions, ClaimRepository claims, RoleService roles) {
        this.roles = roles;
        this.claims = claims;
        this.versions = versions;
        this.chains = chains;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The rates set on the sheet, by grade, each grade's in the order they take effect. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return chains.ratesOf(sheetId).stream()
                .sorted(Comparator.comparing(Rate::getGrade).thenComparing(Rate.BY_FROM))
                .toList();
    }

    /** The ids of the sheet's ancestors whose rates stand in for its own, nearest first. */
    @Transactional(readOnly = true)
    public List<String> ancestorsOf(String sheetId) {
        return chains.ancestorsOf(sheetId);
    }

    /** What a candidate rate for a grade would change were it to apply on every date; nothing is stored. */
    @Transactional(readOnly = true)
    public Rollup.RatePreview preview(String sheetId, String grade, BigDecimal rate) {
        return rollup.preview(sheetId, grade, rate);
    }

    /** Set a grade's rate from a date (null: from the beginning of time) through a to-date (null: it
     *  does not end). When the grade already has a rate from that same date, the value it replaces is
     *  kept as an older version and the new one becomes the newest version, the one in force. A to-date
     *  before the from-date is refused. A rate from a date on or after the sheet's today is forward-only: it applies
     *  only to claims added after it is saved, and a claim already there keeps the rate that applied to it (the
     *  version it replaces included); any other applies to every claim. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal rate) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rate cannot end before it starts");
        }
        Sheet sheet = sheets.load(sheetId, user);
        SheetService.requireGrade(sheet, grade);
        LocalDate today = sheet.getToday();
        Long claimsAfter = from != null && !from.isBefore(today) ? claims.lastId() : null;
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Rollup.Figures before = rollup.of(sheetId);
        Rate row = rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFromDate(), from))
                .findFirst()
                .orElse(null);
        if (row == null) {
            row = new Rate(sheetId, grade, from, scaled);
        } else {
            versions.save(new RateVersion(row.getId(), row.getVersion(), row.getRate(), row.getToDate(),
                    row.getClaimsAfter()));
            row.setVersion(row.getVersion() + 1);
        }
        row.setRate(scaled);
        row.setToDate(to);
        row.setClaimsAfter(claimsAfter);
        Rate saved = chains.withEarlier(List.of(rates.saveAndFlush(row))).get(0);
        audit.recordChangeOnCommit(sheetId, user, "RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getRate().toPlainString()
                + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A steward withdraws the newest version of a grade's rate from a date (null: from the beginning of time): the
     *  version before it, when there is one, applies again; with none the rate is gone (empty). Refused for anyone
     *  but a steward, and when the grade has no rate from that date. */
    @Transactional
    public Optional<Rate> withdraw(String sheetId, String user, String grade, LocalDate from) {
        sheets.load(sheetId, user);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may withdraw a rate");
        }
        Rate row = rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(r -> Objects.equals(r.getFromDate(), from))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rate on this sheet"));
        Rollup.Figures before = rollup.of(sheetId);
        List<RateVersion> older = versions.findByRateIdOrderByVersionAsc(row.getId());
        Optional<Rate> restored;
        if (older.isEmpty()) {
            rates.delete(row);
            rates.flush();
            restored = Optional.empty();
        } else {
            RateVersion previous = older.get(older.size() - 1);
            row.setRate(previous.getRate());
            row.setToDate(previous.getToDate());
            row.setClaimsAfter(previous.getClaimsAfter());
            row.setVersion(previous.getVersion());
            versions.delete(previous);
            versions.flush();
            restored = Optional.of(chains.withEarlier(List.of(rates.saveAndFlush(row))).get(0));
        }
        audit.recordChangeOnCommit(sheetId, user, "RATE_WITHDRAWN sheet=" + sheetId + " by=" + user
                + " grade=" + grade + (from == null ? "" : " from=" + from));
        rollup.recordChanges(sheetId, user, before);
        return restored;
    }
}
