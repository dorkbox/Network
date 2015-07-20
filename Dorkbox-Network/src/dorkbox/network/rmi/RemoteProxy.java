package dorkbox.network.rmi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(value = RetentionPolicy.RUNTIME)
@Inherited
@Target(value = {ElementType.FIELD, ElementType.ANNOTATION_TYPE})
public
@interface RemoteProxy {
}
