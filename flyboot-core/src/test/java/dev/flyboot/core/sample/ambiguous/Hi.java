package dev.flyboot.core.sample.ambiguous;

import dev.flyboot.core.FlyAutowired;
import dev.flyboot.core.FlyComponent;

@FlyComponent
public class Hi implements Greeter {
    public Hi() {
    }

    @FlyAutowired
    public Hi(Hello hello) {
    }
}
