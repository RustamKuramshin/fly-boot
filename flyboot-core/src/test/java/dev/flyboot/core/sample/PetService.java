package dev.flyboot.core.sample;

@Service
public class PetService {
    private final PetRepository repository;
    private final Clock clock;

    PetService(PetRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PetRepository repository() {
        return repository;
    }

    public Clock clock() {
        return clock;
    }
}
