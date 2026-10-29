"""Render the explicitly hypothetical comparison, never a production PnL plot."""
from pathlib import Path
from datetime import datetime
import json
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

base=Path(__file__).resolve().parent
report=json.loads((base/'assumption-replay/report.json').read_text())
assert report['provenance']=='ASSUMPTION_ONLY' and not report['eligible']
fig,ax=plt.subplots(figsize=(10,5),layout='constrained')
for group,color in zip('ABCD',['#536878','#bb7520','#2b8f6a','#7955bb']):
    rows=report['groups'][group]['curve']
    ax.plot([datetime.fromisoformat(r['at']) for r in rows],[float(r['equity']) for r in rows],label=group,color=color,linewidth=1.7)
split=report['groups']['A']['metrics']['history']['split_at']
ax.axvline(datetime.fromisoformat(split),color='#9e9e9e',linestyle='--',label='70/30 boundary')
ax.set_title('ASSUMPTION ONLY — not historical performance acceptance',loc='left',fontweight='bold')
ax.set_ylabel('Net equity (USDT), excluding unknown funding')
ax.set_xlabel('Historical candle time (UTC); synthetic quotes / funding assumptions')
ax.xaxis.set_major_formatter(mdates.DateFormatter('%m-%d %H:%M'))
ax.grid(alpha=.2);ax.legend(ncol=5,loc='lower left',fontsize=9)
fig.savefig(base/'assumption-replay/equity.png',dpi=160)
fig.savefig(base/'assumption-replay/equity.svg')
