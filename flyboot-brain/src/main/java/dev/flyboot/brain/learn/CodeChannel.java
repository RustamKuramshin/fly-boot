package dev.flyboot.brain.learn;

/**
 * One input stream of the route neurons.
 *
 * @param layer  readout population, see {@link dev.flyboot.brain.BrainAtlas}
 * @param cells  number of most active cells kept
 * @param binary kept cells count as 1 instead of their spike counts
 * @param gain   norm of this channel's part of the code, i.e. its weight against the other channels
 */
public record CodeChannel(String layer, int cells, boolean binary, double gain) {
}
