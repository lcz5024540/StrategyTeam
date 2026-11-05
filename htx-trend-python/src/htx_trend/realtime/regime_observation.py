"""Descriptive sample diagnostics, never an entry gate or ranking input."""
from collections import defaultdict
from decimal import Decimal as D
from itertools import combinations
import json

from ..research.store import digest


def family_key(scenario, timing, cost_id):
    return json.dumps([scenario, timing, cost_id], separators=(",", ":"))


def sample_observation(unique, accepted, groups):
    from .indicator_union import EXITS
    from .regime_bayes import group_key

    families = defaultdict(lambda: defaultdict(dict))
    for row in unique:
        key = family_key(row['scenario'], row['timing'], row['cost_id'])
        families[key][row['exit_profile']][row['opportunity_id']] = row
    result = {}
    for key, original in sorted(families.items()):
        scenario, timing, cost = json.loads(key)
        all_ids = set().union(*(set(rows) for rows in original.values()))
        mature, summaries = {}, {}
        warnings = []
        for mode in EXITS:
            group = group_key(scenario, timing, mode, cost)
            rows = original.get(mode, {})
            mature[mode] = {r['opportunity_id']: r for r in accepted.get(group, ())}
            n = len(mature[mode])
            summaries[mode] = {
                'unique_opportunities': len(rows), 'mature_results': n,
                'complete_fraction': str(D(n) / len(rows)) if rows else None,
                'missing_exit_records': len(all_ids - set(rows)),
                'excluded_or_incomplete': len(rows) - n,
                'status_and_exclusion_counts': groups[group]['opportunity_audit'],
            }
            if n < len(rows):
                warnings.append('INCOMPLETE_RESULTS:' + mode)
            if all_ids - set(rows):
                warnings.append('MISSING_EXIT_RECORDS:' + mode)
        sets = [set(mature[mode]) for mode in EXITS]
        if any(ids != sets[0] for ids in sets[1:]):
            warnings.append('UNEQUAL_MATURE_COHORTS')
        paired = []
        mismatched = []
        for ident in sorted(set.intersection(*sets)):
            rows = [mature[mode][ident] for mode in EXITS]
            # An id alone is insufficient if independently supplied labels disagree.
            if len({(r['contract'], r['entry_ms']) for r in rows}) != 1:
                mismatched.append(ident)
            else:
                paired.append(ident)
        if mismatched:
            warnings.append('PAIRED_IDENTITY_MISMATCH')
        first = next(iter(EXITS))
        blocks = len({mature[first][i]['entry_ms'] // 3600000 for i in paired})
        if len(paired) < 20 or blocks < 10:
            warnings.append('PAIRED_SAMPLE_BELOW_EXISTING_EVIDENCE_FLOOR')
        means = {mode: (str(sum((D(str(mature[mode][i]['net_return'])) for i in paired), D(0)) / len(paired))
                        if paired else None) for mode in EXITS}
        comparisons = {}
        for left, right in combinations(EXITS, 2):
            differences = [D(str(mature[left][i]['net_return'])) - D(str(mature[right][i]['net_return'])) for i in paired]
            comparisons[left + '-' + right] = {
                'n': len(paired),
                'mean_net_return_difference': str(sum(differences, D(0)) / len(paired)) if paired else None,
                'left_better': sum(d > 0 for d in differences),
                'right_better': sum(d < 0 for d in differences),
                'equal': sum(d == 0 for d in differences),
            }
        result[key] = {
            'observation_only': True, 'affects_entry': False,
            'unique_opportunities': len(all_ids), 'exits': summaries,
            'paired': {'n': len(paired), 'hour_blocks': blocks,
                       'fraction_of_all_opportunities': str(D(len(paired)) / len(all_ids)) if all_ids else None,
                       'opportunity_ids': paired, 'opportunity_ids_hash': digest(paired),
                       'identity_mismatches': mismatched, 'mean_net_returns': means,
                       'comparisons': comparisons},
            'warnings': warnings,
            'limitations': ['paired completed subset only', 'censoring bias remains',
                            'not financial admission or proof of future advantage'],
        }
    return {'schema': 'regime-sample-observation-v1', 'observation_only': True,
            'affects_entry': False, 'families': result}


def snapshot_observation(value, scenario, timing, cost_id):
    """Old snapshots may lack diagnostics; this must not reject an opportunity."""
    fallback = {'observation_only': True, 'affects_entry': False,
                'warnings': ['SAMPLE_OBSERVATION_UNAVAILABLE']}
    observation = value.get('sample_observation')
    families = observation.get('families') if isinstance(observation, dict) else None
    family = families.get(family_key(scenario, timing, cost_id)) if isinstance(families, dict) else None
    if not isinstance(family, dict):
        return fallback
    # Full cohort IDs remain in the frozen snapshot. Do not copy O(samples)
    # identifiers into every new plan and every subsequent confirmation hash.
    paired = family.get('paired')
    if not isinstance(paired, dict):
        return fallback
    return {**family, 'observation_only': True, 'affects_entry': False,
            'paired': {k: v for k, v in paired.items() if k not in ('opportunity_ids', 'identity_mismatches')}}
