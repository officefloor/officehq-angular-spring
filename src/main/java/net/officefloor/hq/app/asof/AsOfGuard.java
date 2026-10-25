package net.officefloor.hq.app.asof;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refuses every change asked for from a screen set to an as-of date: any request on a sheet other
 * than reading it (a GET, or loading the sheet) is refused before it reaches its controller, so it
 * writes nothing to the store or the audit file.
 */
@Component
public class AsOfGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (AsOf.reads(request) || AsOf.sheetOf(request).isEmpty() || AsOf.of(request).isEmpty()) return true;
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The sheet is shown as of a date: nothing can be changed until the as-of date is cleared.");
    }
}
