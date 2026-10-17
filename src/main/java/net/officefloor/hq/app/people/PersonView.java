package net.officefloor.hq.app.people;

import java.util.List;

/**
 * A person as the API returns it; patrons are referenced by id. thrust is alpha times beta, or null
 * (n/a) when either is blank. momentum is thrust plus three times (gamma minus alpha), or null (n/a)
 * when thrust or gamma is. scaled is momentum times two, and third is scaled divided by three (rounded
 * down, see {@link SheetMath#divide}); both null (n/a) when momentum is.
 */
public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma, boolean hold,
        List<Long> patronIds, Long thrust, Long momentum, Long scaled, Long third) {

    static PersonView of(Person p) {
        return new PersonView(p.getId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(), p.isHold(),
                p.getPatrons().stream().map(Person::getId).sorted().toList(), thrust(p), momentum(p),
                scaled(p), third(p));
    }

    private static Long thrust(Person p) {
        if (p.getAlpha() == null || p.getBeta() == null) {
            return null;
        }
        return (long) p.getAlpha() * p.getBeta();
    }

    private static Long momentum(Person p) {
        Long thrust = thrust(p);
        if (thrust == null || p.getGamma() == null) {
            return null;
        }
        return thrust + 3L * ((long) p.getGamma() - p.getAlpha());
    }

    private static Long scaled(Person p) {
        Long momentum = momentum(p);
        return momentum == null ? null : momentum * 2L;
    }

    private static Long third(Person p) {
        Long scaled = scaled(p);
        return scaled == null ? null : SheetMath.divide(scaled, 3L);
    }
}
