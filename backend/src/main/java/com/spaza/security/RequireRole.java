package com.spaza.security;

import java.lang.annotation.*;

/** Restricts a controller or handler to the listed roles (OWNER, SUPPLIER, ADMIN). Checked by AuthInterceptor. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
  String[] value();
}
