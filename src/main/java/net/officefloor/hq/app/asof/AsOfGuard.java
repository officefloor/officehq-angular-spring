package net.officefloor.hq.app.asof;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import net.officefloor.hq.app.settings.ViewerGuard;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A screen showing a sheet as of a date sends that date with each request ({@link #HEADER}). Such a request is
 * answered from the sheet as it stood at the end of the date (see {@link AsOfService#storeAsOf}), and every request
 * that changes the sheet is refused before it reaches its handler, so it writes nothing. A handler marked
 * {@link AsOfReads} changes nothing and is answered as of the date.
 */
@Component
public class AsOfGuard implements HandlerInterceptor {

    /** The request header carrying the date the screen shows the sheet as of (yyyy-mm-dd). */
    public static final String HEADER = "X-As-Of";

    private final AsOfService asOf;

    public AsOfGuard(AsOfService asOf) {
        this.asOf = asOf;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER);
        String sheetId = ViewerGuard.sheetId(request);
        if (header == null || header.isBlank() || !(handler instanceof HandlerMethod method) || sheetId == null) {
            return true;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(header.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The as-of date is not a date.");
        }
        if (!HttpMethod.GET.matches(request.getMethod()) && !method.hasMethodAnnotation(AsOfReads.class)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The sheet is shown as of a date; nothing on it can change.");
        }
        AsOfContext.enter(asOf.storeAsOf(sheetId, day));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        AsOfContext.leave();
    }
}
