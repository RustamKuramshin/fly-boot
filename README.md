# FlyBoot

A Spring Boot-style web framework in which HTTP request routing is done by a simulated brain of the fruit fly *Drosophila melanogaster*, built from the real connectome: 138,639 neurons and 15 million connections.

```java
@FlyBootApplication
public class FlyClinicApplication {
    public static void main(String[] args) {
        FlyApplication.run(FlyClinicApplication.class, args);
    }
}

@RestController
@RequestMapping("/keepers")
public class KeeperController {
    @GetMapping("/{id}")
    public Keeper get(@PathVariable long id) { ... }
}
```

```
$ curl -i localhost:8080/keepers/1
HTTP/1.1 200 OK
X-Fly-Route: GET /keepers/{id}
X-Fly-Belief: 0.916
X-Fly-Spikes: 19312
X-Fly-Sniff-Ms: 75.0
{"id":1,"firstName":"Thomas","lastName":"Morgan","lab":"Fly Room, Columbia"}
```

## How it works

1. On startup the framework finds the controllers and their `@GetMapping`, `@PostMapping` and so on.
2. The fly "sniffs" synthetic requests to every endpoint and decoys that match none of them. A dopamine prediction error tunes the synapses from Kenyon cells and projection neurons onto route neurons. The result is cached in `.flyboot/`.
3. Every request becomes an odor: the method excites a pheromone glomerulus, path features excite ordinary glomeruli. Then 75 ms of brain activity are simulated with the Shiu et al. 2024 model, validated against the original Brian2 code.
4. The most confident route neuron wins. If it is the "unknown odor" neuron, the answer is 404. The dispatcher never matches path templates: it only carries out the fly's decision.

Accuracy on unseen path variable values is 96-98%, about 70-75% of requests to nonexistent addresses are rejected, routing a request takes 50-75 ms. With the brain's wiring shuffled (`--flyboot.brain.control=shuffled`) accuracy drops to 22%.

## Quick start

You need JDK 25 (`.java-version` for jenv) and Maven.

```bash
./scripts/download-data.sh && mvn install && mvn -q -pl flyboot-connectome-tool exec:java
```

```bash
mvn -q -pl flyboot-demo exec:exec -Ddemo.args="--server.port=8080"
```

```bash
curl -i localhost:8080/actuator/brain
```


## Modules

| Module | What is inside |
|---|---|
| `flyboot-brain` | Connectome, LIF engine (a port of Shiu et al. 2024, validated against Brian2), population atlas, HTTP "nose", fly pool, route learning (`FlyRouter`) |
| `flyboot-core` | `@Component`, `@FlyBootApplication`, package scanning, constructor injection |
| `flyboot-web` | `@RestController`, `@GetMapping`..., `@PathVariable`, `@RequestParam`, `@RequestBody`, `ResponseEntity`; HTTP server and a dispatcher where the fly picks the route |
| `flyboot-starter` | `FlyApplication.run`, `application.properties`, banner, `/actuator/*` |
| `flyboot-connectome-tool` | Converts FlyWire v783 to `.flyb` with DuckDB |
| `flyboot-lab` | Experiments of phases 0-2 and a JMH benchmark |
| `tools/reference` | Reference run of the original model on Brian2 and the comparison |

## How the project was built

- Research and plan.
- Phase 0 - feasibility check: engine validated against Brian2 (r = 0.9997), odor discrimination, shuffled-wiring control.
- Phase 1 - brain module: the "nose", the fly pool, a 2x faster engine.
- Phase 2 - learning: why Kenyon cells, APL x3, prediction error and decoys for 404.
- Phase 3 - annotations, server, dispatcher.
- Phase 4 - starter, actuator, demo.

## Data and attribution

- FlyWire FAFB v783 connectome, license [CC BY-NC 4.0](https://flywire.ai/tos) (non-commercial use only):
  - Dorkenwald et al., Nature 2024;
  - Schlegel et al., Nature 2024;
  - neurotransmitter predictions: Eckstein et al., Cell 2024.
- Neuron model and prepared connectivity files: Shiu et al., "A Drosophila computational brain model reveals sensorimotor processing", Nature 2024, [philshiu/Drosophila_brain_model](https://github.com/philshiu/Drosophila_brain_model) (MIT). A copy of `model.py` is in `tools/reference/vendor`.
- The learning rule follows Bennett, Philippides, Nowotny (Nat Commun 2021): dopaminergic neurons compute a reward prediction error.
