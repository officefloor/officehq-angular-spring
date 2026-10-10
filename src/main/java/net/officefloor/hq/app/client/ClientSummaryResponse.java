package net.officefloor.hq.app.client;

import java.math.BigDecimal;

/**
 * At-a-glance counts of a client's projects and contacts, and the total ever billed to them in their currency: the
 * sum of every invoice issued to them (drafts, not yet issued, and cancelled invoices are left out), net of the
 * credit notes against them and what has been written off. Also their
 * lifetime value in their currency: the money they have actually paid, less what has been refunded to them.
 */
public record ClientSummaryResponse(long projectCount, long contactCount, BigDecimal lifetimeBilled,
        BigDecimal lifetimeValue) {
}
