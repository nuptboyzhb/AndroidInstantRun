package com.android.tools.ir.api;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target({java.lang.annotation.ElementType.PACKAGE, java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.CONSTRUCTOR, java.lang.annotation.ElementType.METHOD})
public @interface DisableInstantRun {}


/* Location:              D:\decodeapk\new_aliptrip\instant-run.jar!\com\android\tools\ir\api\DisableInstantRun.class
 * Java compiler version: 6 (50.0)
 * JD-Core Version:       0.7.1
 */