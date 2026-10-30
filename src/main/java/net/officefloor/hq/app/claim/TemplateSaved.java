package net.officefloor.hq.app.claim;

import java.math.BigDecimal;

/** A claim template was saved, new or replacing the one of its name ({@code TEMPLATE_SAVED}). */
public record TemplateSaved(String sheetId, String user, String name, BigDecimal amount, String note) {
}
