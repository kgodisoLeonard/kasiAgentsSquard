import { esc, money, toast } from '../ui.js';

export async function renderMandates(root, api, alive) {
  const data = await api.mandates();
  if (!alive()) return;
  let mandates = data.mandates;

  root.innerHTML = `
    <div class="grid g-3">
      <div class="span-2"><div class="grid g-2" id="list"></div></div>
      <div class="card" style="align-self:start">
        <h3>New mandate</h3><p class="sub">Limits are enforced by the backend, outside the AI agent</p>
        <form id="form" class="stack">
          <div class="field"><label>Title</label><input class="input" name="title" required placeholder="e.g. Bread supplier weekly"/></div>
          <div class="grid g-2" style="gap:12px">
            <div class="field"><label>Per-tx cap (R)</label><input class="input" name="perTxCap" type="number" min="1" required value="500"/></div>
            <div class="field"><label>Daily cap (R)</label><input class="input" name="dailyCap" type="number" min="1" required value="2000"/></div>
          </div>
          <div class="field"><label>Expires</label><input class="input" name="expires" type="date" required/></div>
          <button class="btn btn-primary btn-block" type="submit">Create mandate</button>
        </form>
      </div>
    </div>`;

  const $ = (s) => root.querySelector(s);
  const d = new Date(); d.setMonth(d.getMonth() + 1);
  $('[name=expires]').value = d.toISOString().slice(0, 10);

  function draw() {
    $('#list').innerHTML = mandates.map((m) => {
      const pct = Math.min(100, Math.round((m.spentToday / m.dailyCap) * 100));
      const tone = pct >= 85 ? 'rose' : pct >= 50 ? 'amber' : '';
      return `
      <div class="card">
        <div class="card-head" style="margin-bottom:12px">
          <span class="chip violet mono">${esc(m.id)}</span>
          <span class="chip green">● ${esc(m.status)}</span>
        </div>
        <h3 style="margin-bottom:14px">${esc(m.title)}</h3>
        <div class="row" style="font-size:13px;margin-bottom:8px"><span class="k">Spent today</span><span class="mono">${money(m.spentToday)} / ${money(m.dailyCap)}</span></div>
        <div class="progress ${tone}"><div style="width:${pct}%"></div></div>
        <div class="divider"></div>
        <div class="stack-sm" style="font-size:13px">
          <div class="row"><span class="k">Per-transaction cap</span><span class="mono">${money(m.perTxCap)}</span></div>
          <div class="row"><span class="k">Expires</span><span>${esc(m.expires)}</span></div>
        </div>
      </div>`;
    }).join('');
  }
  draw();

  $('#form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    if (+f.perTxCap > +f.dailyCap) return toast('Per-tx cap cannot exceed the daily cap', 'error');
    try {
      const r = await api.createMandate({ title: f.title.trim(), perTxCap: +f.perTxCap, dailyCap: +f.dailyCap, expires: f.expires });
      mandates = [...mandates, r.mandate];
      draw(); e.target.reset(); $('[name=expires]').value = d.toISOString().slice(0, 10);
      toast(`Mandate ${r.mandate.id} is active`);
    } catch (err) { toast(err.message, 'error'); }
  });
}
