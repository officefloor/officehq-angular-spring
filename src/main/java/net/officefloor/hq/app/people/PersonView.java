package net.officefloor.hq.app.people;

import java.util.List;

/**
 * A person as the API returns it; patrons are referenced by id. thrust is alpha times beta, or null
 * (n/a) when either is blank. momentum is thrust plus three times (gamma minus alpha), or null (n/a)
 * when thrust or gamma is. scaled is momentum times two, and third is scaled divided by three (rounded
 * to the nearest, see {@link SheetMath#divide}); both null (n/a) when momentum is. norm is scaled minus the
 * sheet's average scaled (over the people not on hold whose scaled is not n/a, rounded like every divide), null
 * (n/a) when scaled is. backing is the momentum of each patron added up (n/a patrons add nothing), null
 * (n/a) when the person's own momentum is. quarter is scaled divided by four (rounded like every divide)
 * and tier is SINK when scaled is below zero, STEADY from 0 up to 199 and SURGE at 200 or more; both
 * null (n/a) when scaled is. keel is scaled plus momentum, null (n/a) when momentum is.
 * fathom is keel plus momentum and draft is keel plus thrust; both null (n/a) when keel is.
 * cascade is fathom plus scaled, null (n/a) when fathom is. trim is keel minus thrust, null (n/a)
 * when keel is.
 */
public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma, boolean hold,
        List<Long> patronIds, Long thrust, Long momentum, Long scaled, Long third, Long norm,
        Long backing, Long quarter, String tier, Long keel,
        Long fathom, Long draft, Long cascade, Long trim) {

    static PersonView of(Person p, Long averageScaled) {
        return new PersonView(p.getId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(), p.isHold(),
                p.getPatrons().stream().map(Person::getId).sorted().toList(), thrust(p), momentum(p),
                scaled(p), third(p), norm(p, averageScaled), backing(p),
                quarter(p), tier(p), keel(p), fathom(p), draft(p), cascade(p), trim(p));
    }

    /** The average scaled across the people, skipping n/a and people on hold; null when nobody has one. */
    static Long averageScaled(List<Person> people) {
        List<Long> values = people.stream().filter(p -> !p.isHold()).map(PersonView::scaled)
                .filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        long sum = values.stream().mapToLong(Long::longValue).sum();
        return SheetMath.divide(sum, values.size());
    }

    private static Long norm(Person p, Long averageScaled) {
        Long scaled = scaled(p);
        return scaled == null || averageScaled == null ? null : scaled - averageScaled;
    }

    private static Long backing(Person p) {
        if (momentum(p) == null) {
            return null;
        }
        return p.getPatrons().stream().map(PersonView::momentum).filter(m -> m != null)
                .mapToLong(Long::longValue).sum();
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

    private static Long quarter(Person p) {
        Long scaled = scaled(p);
        return scaled == null ? null : SheetMath.divide(scaled, 4L);
    }

    private static Long keel(Person p) {
        Long momentum = momentum(p);
        return momentum == null ? null : scaled(p) + momentum;
    }

    private static Long fathom(Person p) {
        Long keel = keel(p);
        return keel == null ? null : keel + momentum(p);
    }

    private static Long draft(Person p) {
        Long keel = keel(p);
        return keel == null ? null : keel + thrust(p);
    }

    private static Long cascade(Person p) {
        Long fathom = fathom(p);
        return fathom == null ? null : fathom + scaled(p);
    }

    private static Long trim(Person p) {
        Long keel = keel(p);
        return keel == null ? null : keel - thrust(p);
    }

    private static String tier(Person p) {
        Long scaled = scaled(p);
        if (scaled == null) {
            return null;
        }
        if (scaled < 0) {
            return "SINK";
        }
        return scaled < 200 ? "STEADY" : "SURGE";
    }
}
