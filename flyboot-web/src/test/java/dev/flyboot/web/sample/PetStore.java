package dev.flyboot.web.sample;

import dev.flyboot.core.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PetStore {
    private final Map<Long, Pet> pets = new ConcurrentSkipListMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    public PetStore() {
        save(new Pet(1, "Muha", "fly"));
        save(new Pet(2, "Barsik", "cat"));
        save(new Pet(3, "Sharik", "dog"));
    }

    public Pet save(Pet pet) {
        Pet stored = pet.id() > 0 ? pet : new Pet(ids.incrementAndGet(), pet.name(), pet.kind());
        pets.put(stored.id(), stored);
        return stored;
    }

    public Optional<Pet> find(long id) {
        return Optional.ofNullable(pets.get(id));
    }

    public boolean delete(long id) {
        return pets.remove(id) != null;
    }

    public List<Pet> all() {
        return List.copyOf(pets.values());
    }
}
