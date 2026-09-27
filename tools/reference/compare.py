"""Compares per-neuron firing rates of the Java engine with the Brian2 reference.

Usage: .venv/bin/python compare.py <rate_hz>
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd

MN9 = 720575940660219265
res = Path(__file__).parent / "results"
rate = sys.argv[1] if len(sys.argv) > 1 else "150"
b = pd.read_csv(res / f"brian2_sugar_{rate}Hz.csv").set_index("flywire_id").rate_hz
j = pd.read_csv(res / f"java_sugar_{rate}Hz.csv").set_index("flywire_id").rate_hz
both = pd.concat([b.rename("brian2"), j.rename("java")], axis=1).fillna(0.0)

active_b, active_j = set(b.index), set(j.index)
strong = both[(both.brian2 >= 5) | (both.java >= 5)]
print(f"input {rate} Hz, 30 trials each")
print(f"active neurons: brian2 {len(active_b)}, java {len(active_j)}, common {len(active_b & active_j)}")
print(f"MN9: brian2 {both.brian2.get(MN9, 0):.1f} Hz, java {both.java.get(MN9, 0):.1f} Hz")
print(f"Pearson r over union of active neurons: {np.corrcoef(both.brian2, both.java)[0, 1]:.4f}")
print(f"neurons >= 5 Hz: {len(strong)}, median |java - brian2| / brian2: "
      f"{(abs(strong.java - strong.brian2) / strong.brian2.clip(lower=1)).median():.3f}")
top = both.sort_values("brian2", ascending=False).head(12)
print(top.round(1).to_string())
