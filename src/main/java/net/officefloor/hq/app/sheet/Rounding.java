package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Optional;

/** A sheet's rounding mode, as its dated setting "rounding" stores it: a code in the setting's value
 *  (a blank value is none: a claim then rounds by the policy date). */
public enum Rounding {
    HALF_UP("half-up", 1, RoundingMode.HALF_UP),
    HALF_EVEN("half-even", 2, RoundingMode.HALF_EVEN);

    /** The key of the dated setting. */
    public static final String KEY = "rounding";

    private final String text;
    private final BigDecimal code;
    private final RoundingMode mode;

    Rounding(String text, int code, RoundingMode mode) {
        this.text = text;
        this.code = BigDecimal.valueOf(code).setScale(2);
        this.mode = mode;
    }

    /** The mode as it is typed and shown: half-up or half-even. */
    public String text() {
        return text;
    }

    /** The code the setting stores. */
    public BigDecimal code() {
        return code;
    }

    public RoundingMode mode() {
        return mode;
    }

    /** The mode typed; empty when it is none of them. */
    public static Optional<Rounding> ofText(String text) {
        return Arrays.stream(values()).filter(r -> r.text.equals(text)).findFirst();
    }

    /** The mode a stored code stands for; empty when it is blank. */
    public static Optional<Rounding> ofCode(BigDecimal code) {
        return code == null ? Optional.empty()
                : Arrays.stream(values()).filter(r -> r.code.compareTo(code) == 0).findFirst();
    }
}
