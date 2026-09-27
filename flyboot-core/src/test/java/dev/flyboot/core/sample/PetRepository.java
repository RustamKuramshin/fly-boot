package dev.flyboot.core.sample;

import dev.flyboot.core.Component;

@Component
public class PetRepository {
    public String find(long id) {
        return "pet-" + id;
    }
}
