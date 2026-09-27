package dev.flyboot.core.sample.ambiguous;

import dev.flyboot.core.Autowired;
import dev.flyboot.core.Component;

@Component
public class Hi implements Greeter {
    public Hi() {
    }

    @Autowired
    public Hi(Hello hello) {
    }
}
