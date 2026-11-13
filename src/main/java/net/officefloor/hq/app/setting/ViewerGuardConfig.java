package net.officefloor.hq.app.setting;

import net.officefloor.hq.app.sheet.ArchivedGuard;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link AsOfGuard}, the {@link ViewerGuard} and the {@link ArchivedGuard} in front of every sheet-scoped request. */
@Configuration
public class ViewerGuardConfig implements WebMvcConfigurer {

    private final ViewerGuard guard;
    private final ArchivedGuard archived;
    private final AsOfGuard asOf;

    public ViewerGuardConfig(ViewerGuard guard, ArchivedGuard archived, AsOfGuard asOf) {
        this.guard = guard;
        this.archived = archived;
        this.asOf = asOf;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(asOf).addPathPatterns("/api/sheets/**");
        registry.addInterceptor(guard).addPathPatterns("/api/sheets/**");
        registry.addInterceptor(archived).addPathPatterns("/api/sheets/**");
    }
}
