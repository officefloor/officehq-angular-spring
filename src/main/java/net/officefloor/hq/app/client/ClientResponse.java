package net.officefloor.hq.app.client;

import java.math.BigDecimal;
import net.officefloor.hq.app.contact.Contact;

public record ClientResponse(Long id, String name, String email, String phone, String taxNumber, String billingAddress, String language, String accountManager, boolean taxInclusive, boolean taxExempt, boolean keyAccount, BigDecimal defaultDiscountPct, boolean archived, PrimaryContact primaryContact,
        String currency, BigDecimal creditLimit, Integer paymentTermsDays, BigDecimal outstanding) {

    /** Who the client's main contact is; null when none has been chosen. */
    public record PrimaryContact(Long id, String name) {
    }

    /** A client with what they still owe, in their currency: what is left to pay on their sent, not yet fully paid invoices. */
    public static ClientResponse from(Client client, BigDecimal outstanding) {
        Contact primary = client.getPrimaryContact();
        return new ClientResponse(client.getId(), client.getName(), client.getEmail(), client.getPhone(), client.getTaxNumber(), client.getBillingAddress(), client.getLanguage(), client.getAccountManager(), client.isTaxInclusive(), client.isTaxExempt(), client.isKeyAccount(), client.getDefaultDiscountPct(), client.isArchived(),
                primary == null ? null : new PrimaryContact(primary.getId(), primary.getName()),
                client.getCurrency(), client.getCreditLimit(), client.getPaymentTermsDays(), outstanding);
    }
}
