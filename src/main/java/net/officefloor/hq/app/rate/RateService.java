package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RateService {

    /** The grades the rate form takes. */
    private static final Pattern GRADE = Pattern.compile("[A-Z]{1,3}");

    /** A percent as the rate form takes it: 0.00 or more with up to two decimals. */
    private static final Pattern PCT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");

    /** A date as the rate form takes it. */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final RateRepository rates;
    private final RateVersionRepository versions;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final RollupService rollup;
    private final Audit audit;

    public RateService(RateRepository rates, RateVersionRepository versions, SheetService sheets,
            ClaimRepository claims, RollupService rollup, Audit audit) {
        this.rates = rates;
        this.claims = claims;
        this.versions = versions;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set a grade's rate from a date (null: from the beginning of time) through a date (null: for
     *  good) on the sheet: a grade and from-date that already have a rate keep the value it replaces as
     *  an older version and take the new one as their newest, the one in force (the sheet is created first if it was never loaded).
     *  One with a from-date on or after the sheet's today is forward-only: it applies only to claims
     *  added after it is saved, those already on any sheet keeping the rates that applied to them. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        sheets.requireGrade(sheetId, grade);
        LocalDate today = sheets.load(sheetId, user).getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = pct.setScale(2);
        Long afterClaim = from != null && !from.isBefore(today) ? claims.findLastId() : null;
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        try {
            if (rate == null) {
                rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, scaled, 1, afterClaim, List.of()));
            } else {
                versions.save(new RateVersion(rate.getId(), rate.getVersion(), rate.getPct(), rate.getTo()));
                rate.newVersion(scaled, to, afterClaim);
                rates.flush();
            }
        } catch (DataIntegrityViolationException e) {
            // set by another user at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rate changed at the same moment");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + grade + " rate=" + scaled.toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        rollup.recordChanges(sheetId, user, before);
        return rate;
    }

    /** One pasted line as the rate form would submit it. */
    private record Line(String grade, BigDecimal pct, LocalDate from, LocalDate to) {
    }

    /** Set a rate for each line of grade,rate,from,to, in order, each exactly as the form sets it:
     *  the from- and to-dates may be absent or blank; blank lines are skipped. Every line is
     *  checked first by the rate form's rules (400 when any is not a rate the form takes, or there
     *  is none); one change, so when any line is refused nothing is set. */
    @Transactional
    public List<Rate> importRates(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n|\\r")) {
            n++;
            if (raw.isBlank()) continue;
            String[] f = raw.split(",", -1);
            if (f.length < 2 || f.length > 4) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": give grade,rate,from,to");
            }
            String grade = f[0].trim();
            String pct = f[1].trim();
            String from = f.length > 2 ? f[2].trim() : "";
            String to = f.length > 3 ? f[3].trim() : "";
            if (!GRADE.matcher(grade).matches() || !PCT.matcher(pct).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": not a rate the form takes");
            }
            LocalDate fromDate = date(from, n);
            LocalDate toDate = date(to, n);
            if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": to-date before from-date");
            }
            lines.add(new Line(grade, new BigDecimal(pct), fromDate, toDate));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Rate> set = new ArrayList<>();
        for (Line line : lines) {
            set.add(set(sheetId, user, line.grade(), line.from(), line.to(), line.pct()));
        }
        return set;
    }

    /** A pasted date: null when blank; 400 when it is not a date as yyyy-mm-dd. */
    private static LocalDate date(String value, int line) {
        if (value.isEmpty()) return null;
        try {
            if (DATE.matcher(value).matches()) return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            // falls through to the refusal
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + line + ": not a date as yyyy-mm-dd");
    }
}
