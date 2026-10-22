package dev.flyboot.web.sample;

import dev.flyboot.web.ResponseEntity;
import dev.flyboot.web.ResponseStatusException;
import dev.flyboot.web.annotation.FlyDeleteMapping;
import dev.flyboot.web.annotation.FlyGetMapping;
import dev.flyboot.web.annotation.FlyPathVariable;
import dev.flyboot.web.annotation.FlyPostMapping;
import dev.flyboot.web.annotation.FlyRequestBody;
import dev.flyboot.web.annotation.FlyRequestMapping;
import dev.flyboot.web.annotation.FlyRequestParam;
import dev.flyboot.web.annotation.FlyResponseStatus;
import dev.flyboot.web.annotation.FlyController;

import java.util.List;

@FlyController
@FlyRequestMapping("/pets")
public class PetController {

    private final PetStore store;

    public PetController(PetStore store) {
        this.store = store;
    }

    @FlyGetMapping
    public List<Pet> list(@FlyRequestParam(defaultValue = "10") int limit, @FlyRequestParam(required = false) List<String> kind) {
        return store.all().stream().filter(p -> kind.isEmpty() || kind.contains(p.kind())).limit(limit).toList();
    }

    @FlyGetMapping("/{id}")
    public Pet get(@FlyPathVariable long id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(404, "No pet " + id));
    }

    @FlyGetMapping("/{id}/name")
    public String name(@FlyPathVariable long id) {
        return get(id).name();
    }

    @FlyPostMapping
    @FlyResponseStatus(201)
    public Pet create(@FlyRequestBody Pet pet) {
        return store.save(pet);
    }

    @FlyDeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@FlyPathVariable long id) {
        return store.delete(id) ? ResponseEntity.noContent() : ResponseEntity.status(404, null);
    }
}
