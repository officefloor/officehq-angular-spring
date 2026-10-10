package net.officefloor.hq.app.invoice;

import jakarta.validation.constraints.Size;

/** Payload to put the client's purchase-order number on an invoice; leave it out or blank to clear it. */
public record PoNumberRequest(@Size(max = 50) String poNumber) {
}
