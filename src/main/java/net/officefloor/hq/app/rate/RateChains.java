package net.officefloor.hq.app.rate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/** Reads the line of rates a sheet's dues are read at, as the store holds them now. */
@Service
public class RateChains {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final RateVersionRepository versions;

    public RateChains(RateRepository rates, SheetRepository sheets, RateVersionRepository versions) {
        this.rates = rates;
        this.sheets = sheets;
        this.versions = versions;
    }

    /** A sheet's rates as the store holds them now, each forward-only one with the older versions a claim it does
     *  not apply to may take. */
    public List<Rate> ratesOf(String sheetId) {
        return withEarlier(rates.findBySheetId(sheetId));
    }

    /** The rates given, each forward-only one with the older versions a claim it does not apply to may take: newest
     *  first, down to the first that applies to every claim. */
    public List<Rate> withEarlier(List<Rate> sheetRates) {
        for (Rate rate : sheetRates) {
            List<RateVersion> earlier = new ArrayList<>();
            if (rate.getClaimsAfter() != null && rate.getId() != null) {
                List<RateVersion> older = versions.findByRateIdOrderByVersionAsc(rate.getId());
                for (int i = older.size() - 1; i >= 0; i--) {
                    earlier.add(older.get(i));
                    if (older.get(i).getClaimsAfter() == null) {
                        break;
                    }
                }
            }
            rate.setEarlier(earlier);
        }
        return sheetRates;
    }

    /** The sheet's own rates, then each of its ancestors' up to {@link RateChain#ANCESTORS} levels up. */
    public RateChain of(String sheetId) {
        return new RateChain(Stream.concat(Stream.of(sheetId), ancestorsOf(sheetId).stream())
                .map(this::ratesOf).toList());
    }

    /** The ids of the sheet's ancestors whose rates stand in for its own, nearest first. */
    public List<String> ancestorsOf(String sheetId) {
        return sheets.ancestorsOf(sheetId, RateChain.ANCESTORS);
    }
}
