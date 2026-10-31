package net.officefloor.hq.app.rate;

import java.util.List;
import java.util.stream.Stream;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/** Reads the line of rates a sheet's dues are read at, as the store holds them now. */
@Service
public class RateChains {

    private final RateRepository rates;
    private final SheetRepository sheets;

    public RateChains(RateRepository rates, SheetRepository sheets) {
        this.rates = rates;
        this.sheets = sheets;
    }

    /** The sheet's own rates, then each of its ancestors' up to {@link RateChain#ANCESTORS} levels up. */
    public RateChain of(String sheetId) {
        return new RateChain(Stream.concat(Stream.of(sheetId), ancestorsOf(sheetId).stream())
                .map(rates::findBySheetId).toList());
    }

    /** The ids of the sheet's ancestors whose rates stand in for its own, nearest first. */
    public List<String> ancestorsOf(String sheetId) {
        return sheets.ancestorsOf(sheetId, RateChain.ANCESTORS);
    }
}
