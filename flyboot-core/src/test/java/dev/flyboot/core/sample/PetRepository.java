package dev.flyboot.core.sample;

import dev.flyboot.core.FlyComponent;

@FlyComponent
public class PetRepository {
    public String find(long id) {
        return "pet-" + id;
    }
}
