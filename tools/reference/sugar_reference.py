"""Reference run of the original Shiu et al. 2024 Brian2 model (vendor/model.py, MIT) on FlyWire v783.

Stimulates the right-hemisphere sugar GRNs from the original example notebook and writes the mean firing
rate of every neuron that spiked to results/brian2_sugar_<rate>Hz.csv (flywire_id,rate_hz).

Usage: .venv/bin/python sugar_reference.py [rate_hz] [trials] [processes]
"""
import sys
import time
from pathlib import Path

import pandas as pd
from brian2 import Hz, ms, prefs

sys.path.insert(0, str(Path(__file__).parent / "vendor"))
from model import default_params, run_trial  # noqa: E402
from joblib import Parallel, delayed  # noqa: E402

# Sugar GRNs of the right hemisphere, example.ipynb of philshiu/Drosophila_brain_model
SUGAR_R = [
    720575940624963786, 720575940630233916, 720575940637568838, 720575940638202345, 720575940617000768,
    720575940630797113, 720575940632889389, 720575940621754367, 720575940621502051, 720575940640649691,
    720575940639332736, 720575940616885538, 720575940639198653, 720575940620900446, 720575940617937543,
    720575940632425919, 720575940633143833, 720575940612670570, 720575940628853239, 720575940629176663,
    720575940611875570,
]

ROOT = Path(__file__).resolve().parents[2]
COMP = ROOT / "data/raw/Completeness_783.csv"
CON = ROOT / "data/raw/Connectivity_783.parquet"


def main():
    rate = float(sys.argv[1]) if len(sys.argv) > 1 else 150.0
    trials = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    procs = int(sys.argv[3]) if len(sys.argv) > 3 else 4

    prefs.codegen.target = "cython"
    params = dict(default_params)
    params["r_poi"] = rate * Hz
    params["t_run"] = 1000 * ms
    # Brian2 2.10 rejects assigning the undefined 'w' in the original reset; it has no effect on the model.
    params["eq_rst"] = "v = v_rst; g = 0 * mV"

    comp = pd.read_csv(COMP, index_col=0)
    flyid2i = {f: i for i, f in enumerate(comp.index)}
    exc = [flyid2i[f] for f in SUGAR_R if f in flyid2i]
    print(f"sugar GRNs present in v783: {len(exc)} of {len(SUGAR_R)}, rate {rate} Hz, trials {trials}")

    start = time.time()
    res = Parallel(n_jobs=procs)(delayed(run_trial)(exc, [], [], COMP, CON, params) for _ in range(trials))
    print(f"brian2 wall time: {time.time() - start:.1f} s")

    counts = {}
    for trial in res:
        for i, spikes in trial.items():
            counts[i] = counts.get(i, 0) + len(spikes)
    ids = list(comp.index)
    df = pd.DataFrame({"flywire_id": [ids[i] for i in counts], "rate_hz": [c / trials for c in counts.values()]})
    out = Path(__file__).parent / "results" / f"brian2_sugar_{int(rate)}Hz.csv"
    out.parent.mkdir(exist_ok=True)
    df.sort_values("rate_hz", ascending=False).to_csv(out, index=False)
    mn9 = df.loc[df.flywire_id == 720575940660219265, "rate_hz"]
    print(f"active neurons: {len(df)}, MN9 rate: {mn9.iloc[0] if len(mn9) else 0.0} Hz -> {out}")


if __name__ == "__main__":
    main()
