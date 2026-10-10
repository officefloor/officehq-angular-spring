package net.officefloor.hq.app.client;

import java.math.BigDecimal;
import java.util.List;

/** Clients grouped into revenue bands by how much revenue they bring in, in the home currency, highest band first. */
public record RevenueBandsResponse(String homeCurrency, List<Band> bands) {

    /**
     * One revenue band ("high", "medium" or "low"): the revenue it starts from (inclusive), the revenue it runs up to
     * (exclusive; null for the top band), and how many clients are in it.
     */
    public record Band(String band, BigDecimal from, BigDecimal to, long count) {
    }
}
