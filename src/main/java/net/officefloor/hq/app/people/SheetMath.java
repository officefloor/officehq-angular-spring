package net.officefloor.hq.app.people;

/**
 * Arithmetic shared by every value on the sheet. Every divide on the sheet goes through
 * {@link #divide}, which rounds to the nearest whole number, a half rounding up to the larger
 * number (5 / 2 is 3, -5 / 2 is -2, -8 / 3 is -3).
 */
final class SheetMath {

    private SheetMath() {
    }

    static long divide(long dividend, long divisor) {
        if (divisor < 0) {
            dividend = -dividend;
            divisor = -divisor;
        }
        // floor(dividend / divisor + 1/2)
        return Math.floorDiv(2 * dividend + divisor, 2 * divisor);
    }
}
