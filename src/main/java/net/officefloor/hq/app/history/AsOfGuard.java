package net.officefloor.hq.app.history;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses every change asked for from a screen showing a sheet as of a date (one sending the
 * {@link SheetHistory#AS_OF_HEADER}): any request on a sheet other than a read is refused before it reaches its
 * handler, so it writes nothing. Loading the sheet is left to its handler; see {@link AsOfGuardConfig}.
 */
@Component
public class AsOfGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        String asOf = request.getHeader(SheetHistory.AS_OF_HEADER);
        if (asOf != null && !asOf.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The sheet is shown as of " + asOf.trim() + ": nothing on it can change");
        }
        return true;
    }
}
