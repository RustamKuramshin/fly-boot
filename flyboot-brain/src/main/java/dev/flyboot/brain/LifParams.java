package dev.flyboot.brain;

/**
 * Leaky integrate-and-fire parameters. Defaults reproduce Shiu et al. 2024 (Nature 634:210),
 * {@code default_params} in philshiu/Drosophila_brain_model/model.py; dt is the Brian2 default clock.
 *
 * @param vRestMv      resting potential v_0
 * @param vResetMv     reset potential after a spike
 * @param vThresholdMv spike threshold
 * @param tauMembraneMs membrane time constant
 * @param tauSynapseMs synaptic current decay
 * @param refractoryMs refractory period (0 for Poisson-stimulated neurons, as in the reference)
 * @param delayMs      synaptic delay
 * @param wSynMv       weight of one synapse, the only free parameter of the model
 * @param poissonKickMv jump of v caused by one Poisson input event (w_syn * f_poi = 0.275 * 250)
 * @param dtMs         integration step
 * @param restEpsilonMv a neuron whose |v - v_0| and |g| are both below this value is snapped to rest and skipped
 *                      until it receives input again; 0 updates every neuron on every step, exactly as Brian2
 */
public record LifParams(double vRestMv, double vResetMv, double vThresholdMv, double tauMembraneMs,
                        double tauSynapseMs, double refractoryMs, double delayMs, double wSynMv,
                        double poissonKickMv, double dtMs, double restEpsilonMv) {

    /** Shiu et al. 2024 with rest snapping at 1e-4 mV, far below the 0.04 mV peak of a single synapse. */
    public static LifParams shiu2024() {
        return new LifParams(-52, -52, -45, 20, 5, 2.2, 1.8, 0.275, 0.275 * 250, 0.1, 1e-4);
    }

    public LifParams withWSyn(double wSynMv) {
        return new LifParams(vRestMv, vResetMv, vThresholdMv, tauMembraneMs, tauSynapseMs, refractoryMs, delayMs,
                wSynMv, poissonKickMv, dtMs, restEpsilonMv);
    }

    public LifParams withRestEpsilon(double epsilonMv) {
        return new LifParams(vRestMv, vResetMv, vThresholdMv, tauMembraneMs, tauSynapseMs, refractoryMs, delayMs,
                wSynMv, poissonKickMv, dtMs, epsilonMv);
    }

    int steps(double ms) {
        return (int) Math.round(ms / dtMs);
    }
}
