package com.spaza.config;

import com.spaza.finance.GroupOrderService;
import com.spaza.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;
  private final GroupOrderService groupOrders;

  public WebSecurityConfig(@Lazy AuthInterceptor authInterceptor, @Lazy GroupOrderService groupOrders) {
    this.authInterceptor = authInterceptor; this.groupOrders = groupOrders;
  }

  @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(authInterceptor).addPathPatterns("/api/**"); }

  /** Applies approval deadlines (cancel + refund when the supplier minimum is not met) and delivery deadlines (refund). */
  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  public void applyDeadlines() { groupOrders.processDeadlines(); }
}
