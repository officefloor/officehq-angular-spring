package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.sheet.AsOfFilter;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * The rates a sheet works out what is due from: its own, then its parent's, then the parent's parent,
 * up to {@link #LEVELS} levels up. A grade with no rate in force on a date on a sheet takes the
 * rate in force for it on that date on the nearest ancestor that has one.
 */
@Service
public class RateChain {

    /** How many levels up a sheet's ancestors' rates are looked for. */
    public static final int LEVELS = 3;

    private final SheetRepository sheets;
    private final RateRepository rates;
    private final RateLogRepository rateLog;

    public RateChain(SheetRepository sheets, RateRepository rates, RateLogRepository rateLog) {
        this.sheets = sheets;
        this.rates = rates;
        this.rateLog = rateLog;
    }

    /** The sheet's id, then its parent's, and so on, up to {@link #LEVELS} levels up. */
    public List<String> lineage(String sheetId) {
        List<String> ids = new ArrayList<>();
        ids.add(sheetId);
        String at = sheetId;
        while (ids.size() <= LEVELS) {
            String parent = sheets.findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || ids.contains(parent)) {
                break;
            }
            ids.add(parent);
            at = parent;
        }
        return ids;
    }

    /** Whether naming {@code parentId} as the sheet's parent would make the sheet its own ancestor. */
    public boolean wouldLoop(String sheetId, String parentId) {
        List<String> seen = new ArrayList<>();
        String at = parentId;
        while (at != null && !seen.contains(at)) {
            if (at.equals(sheetId)) {
                return true;
            }
            seen.add(at);
            at = sheets.findById(at).map(Sheet::getParentId).orElse(null);
        }
        return false;
    }

    /**
     * The rates of the sheet and of each ancestor, nearest first, as the store holds them now. When the
     * request is answered as of a date, only the sheet is restored as it stood then (see {@link AsOfFilter}),
     * so each ancestor's rates are those it had at the end of that date.
     */
    public List<List<Rate>> current(String sheetId) {
        Optional<LocalDate> asOf = AsOfFilter.current();
        List<String> ids = lineage(sheetId);
        List<List<Rate>> chain = new ArrayList<>();
        chain.add(rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId));
        for (String id : ids.subList(1, ids.size())) {
            chain.add(asOf.isPresent() ? rateLog.asOf(id, asOf.get()) : rates.findBySheetIdOrderByGradeAscFromDateAsc(id));
        }
        return chain;
    }

    /** The rates of the sheet and of each ancestor, nearest first, as they stood at the end of a day. */
    public List<List<Rate>> asOf(String sheetId, LocalDate day) {
        return lineage(sheetId).stream().map(id -> rateLog.asOf(id, day)).toList();
    }

    /**
     * The grade's rate in force on a date: the sheet's own (as {@link RateRepository#inForce} picks it),
     * else the nearest ancestor's; empty when none has one.
     */
    public static Optional<Rate> inForce(List<List<Rate>> chain, String grade, LocalDate date) {
        for (List<Rate> level : chain) {
            Optional<Rate> rate = RateRepository.inForce(level, grade, date);
            if (rate.isPresent()) {
                return rate;
            }
        }
        return Optional.empty();
    }

    /**
     * The grade's rate in force on a claim's date among the rates that apply to the claim (see
     * {@link Rate#appliesTo}): the sheet's own, else the nearest ancestor's; empty when none has one.
     */
    public static Optional<Rate> inForce(List<List<Rate>> chain, String grade, Claim claim) {
        for (List<Rate> level : chain) {
            Optional<Rate> rate = RateRepository.inForce(level, grade, claim);
            if (rate.isPresent()) {
                return rate;
            }
        }
        return Optional.empty();
    }

    /** The grade's rate in force for a claim on the sheet or, failing that, its nearest ancestor. */
    public Optional<Rate> findInForce(String sheetId, String grade, Claim claim) {
        return inForce(current(sheetId), grade, claim);
    }

    /** The grade's rate in force on a date on the sheet or, failing that, its nearest ancestor. */
    public Optional<Rate> findInForce(String sheetId, String grade, LocalDate date) {
        return inForce(current(sheetId), grade, date);
    }
}
