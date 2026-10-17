package net.officefloor.hq.app.people;

import java.util.List;

/**
 * A person as the API returns it; patrons are referenced by id. A blank alpha, beta or gamma counts as
 * zero in every calculation. thrust is alpha times beta. momentum is thrust plus three times (gamma
 * minus alpha). scaled is momentum times two, and third is scaled divided by three (rounded
 * to the nearest, see {@link SheetMath#divide}); both null (n/a) when momentum is. norm is scaled minus the
 * sheet's average scaled (over the people not on hold whose scaled is not n/a, rounded like every divide), null
 * (n/a) when scaled is. backing is the momentum of each patron added up (n/a patrons add nothing), null
 * (n/a) when the person's own momentum is. quarter is scaled divided by four (rounded like every divide)
 * and tier is SINK when scaled is below zero, STEADY from 0 up to 199 and SURGE at 200 or more; both
 * null (n/a) when scaled is. keel is scaled plus momentum plus norm, null (n/a) when momentum or norm is.
 * fathom is keel plus momentum and draft is keel plus thrust; both null (n/a) when keel is.
 * cascade is fathom plus scaled, null (n/a) when fathom is. trim is keel minus thrust, null (n/a)
 * when keel is. ripple is cascade plus keel, null (n/a) when cascade is. heading is keel plus norm,
 * null (n/a) when keel or norm is. ballast is ripple plus thrust, null (n/a) when ripple is.
 * keystone is ballast plus norm, null (n/a) when ballast or norm is. bearing is keel minus norm, null
 * (n/a) when keel or norm is. pitch is keel plus momentum, null (n/a) when keel is. tackle is keel
 * minus momentum, null (n/a) when keel is. yaw is keel plus scaled, null (n/a) when keel is. heave
 * is keel minus scaled, null (n/a) when keel is. camber is keel plus 200, null (n/a) when keel is.
 * sheer is keel minus 100, null (n/a) when keel is.
 */
public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma, boolean hold,
        List<Long> patronIds, Long thrust, Long momentum, Long scaled, Long third, Long norm,
        Long backing, Long quarter, String tier, Long keel,
        Long fathom, Long draft, Long cascade, Long trim, Long ripple, Long heading, Long ballast,
        Long keystone, Long bearing, Long pitch, Long tackle, Long yaw, Long heave, Long camber,
        Long sheer) {

    static PersonView of(Person p, Long averageScaled) {
        return new PersonView(p.getId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(), p.isHold(),
                p.getPatrons().stream().map(Person::getId).sorted().toList(), thrust(p), momentum(p),
                scaled(p), third(p), norm(p, averageScaled), backing(p),
                quarter(p), tier(p), keel(p, averageScaled), fathom(p, averageScaled),
                draft(p, averageScaled), cascade(p, averageScaled), trim(p, averageScaled),
                ripple(p, averageScaled), heading(p, averageScaled), ballast(p, averageScaled),
                keystone(p, averageScaled), bearing(p, averageScaled), pitch(p, averageScaled),
                tackle(p, averageScaled), yaw(p, averageScaled), heave(p, averageScaled),
                camber(p, averageScaled), sheer(p, averageScaled));
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
        return orZero(p.getAlpha()) * orZero(p.getBeta());
    }

    private static Long momentum(Person p) {
        return thrust(p) + 3L * (orZero(p.getGamma()) - orZero(p.getAlpha()));
    }

    /** A blank number counts as zero. */
    private static long orZero(Integer value) {
        return value == null ? 0L : value;
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

    private static Long keel(Person p, Long averageScaled) {
        Long momentum = momentum(p);
        Long norm = norm(p, averageScaled);
        return momentum == null || norm == null ? null : scaled(p) + momentum + norm;
    }

    private static Long fathom(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + momentum(p);
    }

    private static Long draft(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + thrust(p);
    }

    private static Long cascade(Person p, Long averageScaled) {
        Long fathom = fathom(p, averageScaled);
        return fathom == null ? null : fathom + scaled(p);
    }

    private static Long trim(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - thrust(p);
    }

    private static Long ripple(Person p, Long averageScaled) {
        Long cascade = cascade(p, averageScaled);
        return cascade == null ? null : cascade + keel(p, averageScaled);
    }

    private static Long ballast(Person p, Long averageScaled) {
        Long ripple = ripple(p, averageScaled);
        return ripple == null ? null : ripple + thrust(p);
    }

    private static Long heading(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long norm = norm(p, averageScaled);
        return keel == null || norm == null ? null : keel + norm;
    }

    private static Long keystone(Person p, Long averageScaled) {
        Long ballast = ballast(p, averageScaled);
        Long norm = norm(p, averageScaled);
        return ballast == null || norm == null ? null : ballast + norm;
    }

    private static Long bearing(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long norm = norm(p, averageScaled);
        return keel == null || norm == null ? null : keel - norm;
    }

    private static Long pitch(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + momentum(p);
    }

    private static Long tackle(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - momentum(p);
    }

    private static Long yaw(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + scaled(p);
    }

    private static Long heave(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - scaled(p);
    }

    private static Long camber(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + 200L;
    }

    private static Long sheer(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - 100L;
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
