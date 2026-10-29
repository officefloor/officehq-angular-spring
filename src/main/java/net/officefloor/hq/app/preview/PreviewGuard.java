package net.officefloor.hq.app.preview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import net.officefloor.hq.app.settings.ViewerGuard;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A screen previewing a sheet on a date sends that date with each request ({@link #HEADER}). A read (a GET) is
 * answered over the sheet's current records as if its today were the date; every change ignores it and uses the
 * sheet's real today, so nothing is written by the preview.
 */
@Component
public class PreviewGuard implements HandlerInterceptor {

    /** The request header carrying the date the screen previews the sheet on (yyyy-mm-dd). */
    public static final String HEADER = "X-Preview";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER);
        String sheetId = ViewerGuard.sheetId(request);
        if (header == null || header.isBlank() || sheetId == null || !HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        try {
            PreviewContext.enter(sheetId, LocalDate.parse(header.trim()));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The preview date is not a date.");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        PreviewContext.leave();
    }
}
