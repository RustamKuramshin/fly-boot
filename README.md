# FlyBoot

A Spring Boot-style web framework in which HTTP request routing is done by a simulated brain of the fruit fly *Drosophila melanogaster*, built from the real connectome: 138,639 neurons and 15 million connections.

Status: phase 0, the feasibility check, and phase 1, the brain module, are done.

- Research
- Architecture and plan

## Modules

| Module | What is inside |
|---|---|
| `flyboot-brain` | Connectome, LIF engine (a port of the Shiu et al. 2024 model, validated against Brian2), population atlas, HTTP "nose", fly pool |
| `flyboot-connectome-tool` | Converts FlyWire v783 to `.flyb` with DuckDB |
| `flyboot-lab` | Experiments and a JMH benchmark (`mvn -q -pl flyboot-lab exec:exec`) |
| `tools/reference` | Reference run of the original model on Brian2 and the comparison |

## Quick start

You need JDK 25 (`.java-version` for jenv) and Maven.

```bash
./scripts/download-data.sh
mvn install
mvn -q -pl flyboot-connectome-tool exec:java
java -cp flyboot-brain/target/classes:flyboot-lab/target/classes dev.flyboot.lab.RouteSeparability 75 20
```

## Data and attribution

- FlyWire FAFB v783 connectome, license [CC BY-NC 4.0](https://flywire.ai/tos) (non-commercial use only):
  - Dorkenwald et al., Nature 2024;
  - Schlegel et al., Nature 2024;
  - neurotransmitter predictions: Eckstein et al., Cell 2024.
- Neuron model and prepared connectivity files: Shiu et al., "A Drosophila computational brain model reveals sensorimotor processing", Nature 2024, [philshiu/Drosophila_brain_model](https://github.com/philshiu/Drosophila_brain_model) (MIT). A copy of `model.py` is in `tools/reference/vendor`.
