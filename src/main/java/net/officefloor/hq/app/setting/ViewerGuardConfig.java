package net.officefloor.hq.app.setting;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link ViewerGuard} in front of every sheet-scoped request. */
@Configuration
public class ViewerGuardConfig implements WebMvcConfigurer {

    private final ViewerGuard guard;

    public ViewerGuardConfig(ViewerGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/sheets/**");
    }
}
