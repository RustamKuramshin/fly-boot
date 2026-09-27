package dev.flyboot.web.sample;

import dev.flyboot.web.ResponseEntity;
import dev.flyboot.web.ResponseStatusException;
import dev.flyboot.web.annotation.DeleteMapping;
import dev.flyboot.web.annotation.GetMapping;
import dev.flyboot.web.annotation.PathVariable;
import dev.flyboot.web.annotation.PostMapping;
import dev.flyboot.web.annotation.RequestBody;
import dev.flyboot.web.annotation.RequestMapping;
import dev.flyboot.web.annotation.RequestParam;
import dev.flyboot.web.annotation.ResponseStatus;
import dev.flyboot.web.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/pets")
public class PetController {

    private final PetStore store;

    public PetController(PetStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Pet> list(@RequestParam(defaultValue = "10") int limit, @RequestParam(required = false) List<String> kind) {
        return store.all().stream().filter(p -> kind.isEmpty() || kind.contains(p.kind())).limit(limit).toList();
    }

    @GetMapping("/{id}")
    public Pet get(@PathVariable long id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(404, "No pet " + id));
    }

    @GetMapping("/{id}/name")
    public String name(@PathVariable long id) {
        return get(id).name();
    }

    @PostMapping
    @ResponseStatus(201)
    public Pet create(@RequestBody Pet pet) {
        return store.save(pet);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return store.delete(id) ? ResponseEntity.noContent() : ResponseEntity.status(404, null);
    }
}
