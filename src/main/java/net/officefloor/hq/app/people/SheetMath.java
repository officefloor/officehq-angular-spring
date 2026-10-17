package net.officefloor.hq.app.people;

/**
 * Arithmetic shared by every value on the sheet. Every divide on the sheet goes through
 * {@link #divide}, which rounds down to the whole number below, also below zero (-8 / 3 is -3).
 */
final class SheetMath {

    private SheetMath() {
    }

    static long divide(long dividend, long divisor) {
        return Math.floorDiv(dividend, divisor);
    }
}
