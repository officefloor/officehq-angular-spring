package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates set on a sheet for each grade, each in force from its from-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final History history;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, History history, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.history = history;
        this.audit = audit;
    }

    /** The sheet's rates by grade, each grade's from the earliest. */
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromAscIdAsc(sheetId);
    }

    /** The sheet's rates by grade, each grade's from the earliest, as they stood at the end of a day
     *  (null: as they stand now). */
    public List<Rate> list(String sheetId, LocalDate asOf) {
        if (asOf == null) {
            return list(sheetId);
        }
        return Stream.of("A", "B", "C")
                .flatMap(grade -> history.rates(sheetId, grade, asOf).stream()
                        .sorted(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()))))
                .toList();
    }

    /** The grade's rate in force on a date: the one whose from-date is the latest on or before it
     *  (a rate with no from-date applies from the beginning of time); empty when none applies, or
     *  when that one's to-date is before the date. */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date) {
        return inForce(rates.findBySheetIdAndGradeOrderByFromAsc(sheetId, grade), date);
    }

    /** The grade's rate in force on a date as the rates stood at the end of a day (null: as they
     *  stand now). */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date, LocalDate asOf) {
        return asOf == null ? rateOn(sheetId, grade, date) : inForce(history.rates(sheetId, grade, asOf), date);
    }

    private static Optional<Rate> inForce(List<Rate> theirs, LocalDate date) {
        return theirs.stream()
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(rate -> !rate.endedBy(date));
    }

    /** Set the rate for a grade from a date (null: from the beginning of time) through a date (null:
     *  with no end), replacing the one the grade had from that date. A to-date before the from-date
     *  is refused. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal rate) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        sheets.load(sheetId);
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Optional<Rate> had = from == null
                ? rates.findBySheetIdAndGradeAndFromIsNull(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from);
        Rate saved = had
                .map(existing -> {
                    existing.setRate(scaled);
                    existing.setTo(to);
                    return existing;
                })
                .orElseGet(() -> new Rate(sheetId, grade, from, to, scaled));
        saved = rates.saveAndFlush(saved);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + saved.getRate().toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        return saved;
    }
}
