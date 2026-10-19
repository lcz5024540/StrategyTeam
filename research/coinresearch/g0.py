"""Direction-free G0; arithmetic and RNG ordering follow gate0_power.py."""
import numpy as np
from .config import Config
from .data import iso

def kof(n, frac):
    k = max(3, int(np.floor(frac*n + 0.5)))  # round half up, min 3
    return min(k, n//2)  # need 2k <= n

def random_ls(dataset, rows, cols_mask, frac, ndraw, seed):
    """rows: period idx list; cols_mask: (nP,nC) bool usable. Returns port (ndraw, len(rows)) price LS, fund drag, abs|w| changes."""
    R, F = dataset.R, dataset.F
    nC = len(dataset.coins)
    rng = np.random.default_rng(seed)
    port = np.zeros((ndraw, len(rows))); fdrag = np.zeros_like(port); dw = np.zeros_like(port)
    fl_abs = np.zeros_like(port); fs_abs = np.zeros_like(port)
    prevW = None; prev_i = None
    for q, i in enumerate(rows):
        idx = np.where(cols_mask[i])[0]; n = len(idx); k = int(kof(n, frac))
        if k < 1 or not np.isfinite(R[i, idx]).all() or not np.isfinite(F[i, idx]).all():
            raise ValueError('random groups require finite available observations')
        r = R[i, idx]; f = F[i, idx]
        perm = np.argsort(rng.random((ndraw, n)), axis=1)[:, :2*k]
        L, S = perm[:, :k], perm[:, k:2*k]
        port[:, q] = r[L].mean(1) - r[S].mean(1)
        fL, fS = f[L].mean(1), f[S].mean(1)
        fdrag[:, q] = fL - fS  # HTX: positive rate -> long pays, short receives; drag>0 = cost
        fl_abs[:, q], fs_abs[:, q] = np.abs(fL), np.abs(fS)
        W = np.zeros((ndraw, nC)); W[np.arange(ndraw)[:, None], idx[L]] = 1/k; W[np.arange(ndraw)[:, None], idx[S]] = -1/k
        if prevW is not None and i == prev_i + 1: dw[:, q] = np.abs(W - prevW).sum(1)
        else: dw[:, q] = 4.0  # fresh entry (open both legs + later close) counted as full
        prevW, prev_i = W, i
    return dict(port=port, fdrag=fdrag, dw=dw, flabs=fl_abs, fsabs=fs_abs)

def acf(x, l):
    x = x - x.mean(); return float((x[:-l]*x[l:]).sum()/(x*x).sum())

def summarize(dataset, rows, mask, *, target_bps, cfg=Config()):
    R, F, entries = dataset.R, dataset.F, dataset.entries
    frac, ndraw, seed = cfg.frac, cfg.ndraw, cfg.seed
    Z, TARGET, RT_COST = cfg.z, target_bps, cfg.rt_cost
    if not np.isfinite(TARGET) or TARGET <= 0: raise ValueError("positive explicit target required")
    rows = list(rows); N = len(rows)
    if N < 5: return dict(N=N)
    o = random_ls(dataset, rows, mask, frac, ndraw, seed)
    sd = o["port"].std(1, ddof=1); sdn = (o["port"]-o["fdrag"]).std(1, ddof=1)
    ac = np.array([[acf(o["port"][d], l) for l in (1, 2, 3)] for d in range(ndraw)])
    acm = np.median(ac, 0)
    den = 1 + 2*sum((1-l/N)*acm[l-1] for l in (1, 2, 3)); neff_raw = N/den; neff = min(N, neff_raw)
    # proxy b: residual dispersion -> implied LS sigma = sqrt(mean_p 2 s_p^2 / k_p)
    sp2 = []; ks = []
    for i in rows:
        r = R[i, mask[i]]; n = len(r); k = int(kof(n, frac)); sp2.append(np.var(r - r.mean(), ddof=1)); ks.append(k)
    sp2, ks = np.array(sp2), np.array(ks)
    sig_b = np.sqrt(np.mean(2*sp2/ks)); disp = np.sqrt(sp2)
    turn = o["dw"].mean()/4; cost = RT_COST/2*o["dw"].mean()*1e0  # bps: one-way 6 bps per unit |dw|
    fd = o["fdrag"]*1e4
    sig_a = float(np.median(sd))*1e4
    mad = np.median(1.4826*np.median(np.abs(o["port"]-np.median(o["port"],1,keepdims=True)),1))*1e4
    deff = proxy_deff(o['port'], entries[rows], cfg)
    return dict(**deff, mde_a_deff=None if deff['neff_deff'] is None else float(mde(sig_a, deff['neff_deff'], Z)), target_bps=TARGET, mde_le_target=bool(Z*sig_a/np.sqrt(neff) <= TARGET), N=N, k_med=float(np.median(ks)), n_med=float(np.median([mask[i].sum() for i in rows])),
                sigma_a_bps=sig_a, sigma_a_q05=float(np.quantile(sd, .05))*1e4, sigma_a_q95=float(np.quantile(sd, .95))*1e4,
                sigma_b_bps=float(sig_b)*1e4, sigma_robust_mad_bps=float(mad), N_req_for_target=float((Z*sig_a/TARGET)**2), sigma_net_fund_bps=float(np.median(sdn))*1e4,
                xs_disp_median_bps=float(np.median(disp))*1e4, xs_disp_mean_bps=float(disp.mean())*1e4,
                acf1=float(acm[0]), acf2=float(acm[1]), acf3=float(acm[2]), neff_raw=float(neff_raw), neff=float(neff),
                mde_a=float(Z*sig_a/np.sqrt(neff)), mde_b=float(Z*float(sig_b)*1e4/np.sqrt(neff)), mde_net=float(Z*float(np.median(sdn))*1e4/np.sqrt(neff)),
                turnover_random=float(turn), cost_random_bps=float(cost), cost_full_bps=2*RT_COST,
                fund_drag_mean_bps=float(np.median(fd.mean(1))), fund_drag_sd_bps=float(np.median(fd.std(1, ddof=1))),
                fund_absleg_mean_bps=float((o["flabs"].mean()+o["fsabs"].mean())/2*1e4),
                fund_abs_coin_mean_bps=float(np.mean([np.abs(F[i, mask[i]]).mean() for i in rows])*1e4),
                fund_mkt_mean_bps=float(np.mean([F[i, mask[i]].mean() for i in rows])*1e4),
                fund_worst_bound_bps=float(np.mean([ (lambda f,k: np.sort(f)[-k:].mean()-np.sort(f)[:k].mean())(F[i, mask[i]], int(kof(mask[i].sum(), frac))) for i in rows])*1e4),
                first=iso(entries[rows[0]]), last=iso(entries[rows[-1]]))


def mde(sigma, neff, z=2.486):
    if not np.isfinite(neff) or neff <= 0 or not np.isfinite(sigma) or sigma < 0:
        raise ValueError('invalid sigma or N_eff')
    return z * sigma / np.sqrt(neff)


def acf_neff(series, max_lag=3):
    x = np.asarray(series, dtype=float)
    if len(x) <= max_lag or not np.isfinite(x).all() or np.var(x) == 0:
        raise ValueError('ACF requires nonconstant finite observations')
    ac = np.array([acf(x, lag) for lag in range(1, max_lag+1)])
    den = 1 + 2 * sum((1-lenlag/len(x))*ac[lenlag-1] for lenlag in range(1, max_lag+1))
    if den <= 0: raise ValueError('nonpositive ACF design effect')
    return min(len(x), len(x)/den)


def deff_neff(values, timestamps, *, block_days=5, icc=None):
    """One-way random-effects ANOVA ICC with unequal-size correction.

    Calendar blocks are anchored to Unix epoch. Negative estimated ICC clips to
    zero. Singleton-only data require an externally supplied ICC.
    """
    x, ts = np.asarray(values, float), np.asarray(timestamps, float)
    if x.ndim != 1 or x.shape != ts.shape or len(x) < 2 or not np.isfinite(x).all() or not np.isfinite(ts).all() or block_days <= 0:
        raise ValueError('invalid event observations')
    _, labels = np.unique(np.floor(ts/(block_days*86400)), return_inverse=True)
    sizes = np.bincount(labels); groups = len(sizes); n = len(x)
    if icc is None:
        if groups < 2 or n <= groups: raise ValueError('ICC needs replicated clusters')
        means = np.bincount(labels, weights=x) / sizes
        msb = np.sum(sizes*(means-x.mean())**2)/(groups-1)
        msw = np.sum((x-means[labels])**2)/(n-groups)
        n0 = (n-np.sum(sizes**2)/n)/(groups-1)
        den = msb + (n0-1)*msw
        icc = max(0., (msb-msw)/den) if den > 0 else 0.
    if not np.isfinite(icc) or not 0 <= icc <= 1: raise ValueError('ICC must be in [0,1]')
    m = sizes.mean(); deff = 1+(m-1)*icc
    return dict(N=n, clusters=groups, mean_cluster_size=float(m), icc=float(icc), deff=float(deff), neff=float(n/deff))


def proxy_deff(portfolios, timestamps, cfg):
    """Median of per-draw ANOVA estimates; no additional random calls."""
    clusters = np.unique(np.floor(np.asarray(timestamps)/(cfg.deff_block_days*86400)))
    if cfg.deff_icc is None and (len(clusters) < 2 or len(clusters) == len(timestamps)):
        return dict(neff_deff=None, icc_deff=None, deff_status='INSUFFICIENT_CLUSTER_REPLICATION')
    estimates = [deff_neff(x, timestamps, block_days=cfg.deff_block_days, icc=cfg.deff_icc) for x in portfolios]
    return dict(neff_deff=float(np.median([e['neff'] for e in estimates])), icc_deff=float(np.median([e['icc'] for e in estimates])), deff_status='OK')


def random_events(dataset, rows, *, n_events, ndraw=1000, seed=20261019):
    """Uniform random available period/coin pairs with independent random signs."""
    rows = np.asarray(rows, int)
    pairs = np.argwhere(dataset.EL[rows])
    if not len(pairs) or n_events < 2 or ndraw < 1:
        raise ValueError('at least two pseudo events and available observations required')
    rng = np.random.default_rng(seed)
    chosen = pairs[rng.integers(len(pairs), size=(ndraw, n_events))]
    signs = rng.choice([-1., 1.], size=(ndraw, n_events))
    samples = dataset.R[rows[chosen[..., 0]], chosen[..., 1]] * signs * 1e4
    sd = samples.std(1, ddof=1)
    return dict(n_events=n_events, sigma_event_bps=float(np.median(sd)), q05=float(np.quantile(sd, .05)), q95=float(np.quantile(sd, .95)))


def run_g0(dataset, *, target_bps, cfg=Config()):
    rows = np.where(dataset.EL.sum(1) >= 10)[0]
    cut = int(np.floor(len(rows)*2/3))
    perm = np.random.default_rng(cfg.seed).permutation(len(dataset.coins))
    a = np.zeros(len(dataset.coins), bool); a[perm[:len(a)//2]] = True
    cases = {'full_ge10': (rows, dataset.EL), 'time_explore': (rows[:cut], dataset.EL), 'time_test': (rows[cut:], dataset.EL)}
    for name, selected in [('A', a), ('B', ~a)]:
        mask = dataset.EL & selected[None, :]
        cases[f'coin_{name}_ge10'] = (np.where(mask.sum(1) >= 10)[0], mask)
    result = {name: summarize(dataset, r, mask, target_bps=target_bps, cfg=cfg) for name, (r, mask) in cases.items()}
    result['split'] = {'time_explore': dataset.entries[rows[:cut]].tolist(), 'time_test': dataset.entries[rows[cut:]].tolist(), 'coin_A': [s for j,s in enumerate(dataset.coins) if a[j]], 'coin_B': [s for j,s in enumerate(dataset.coins) if not a[j]], 'seed': cfg.seed}
    return result


def m2_deff(N_nom, rho, m, sd, Z=2.486):
    """Closed-form M2 acceptance; no directional observations required."""
    if not all(np.isfinite(v) for v in (N_nom, rho, m, sd, Z)) or N_nom <= 0 or not 0 <= rho <= 1 or m < 1 or sd < 0 or Z <= 0:
        raise ValueError('invalid M2 parameters')
    deff = 1 + (m-1)*rho
    neff = N_nom/deff
    return dict(deff=deff, neff=neff, mde=float(mde(sd, neff, Z)))
