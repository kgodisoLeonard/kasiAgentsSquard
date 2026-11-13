import { esc, money, toast, when } from '../ui.js';
import { withStepUp } from '../api.js';

export async function renderMandates(root, api, alive) {
  let data = await api.mandate();
  if (!alive()) return;

  const draw = () => {
    const m = data.mandate;
    const allowed = new Set(m?.allowedSuppliers || []);
    const pct = m ? Math.min(100, Math.round((Number(m.spentToday) / Number(m.dailyCap)) * 100)) : 0;
    const until = m?.validUntil || new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10);
    root.innerHTML = `
      <div class="hero"><span class="chip green">Enforced by the backend, outside the agent</span>
        <h3>${m ? 'Your spending mandate is active' : 'No mandate yet: every payment is blocked'}</h3>
        <p>Every payment, whether you start it or the agent does, is checked against these rules before any ledger transaction is created.
        The agent can read your mandate but has no way to change it. Saving changes needs a one-time code, and every change is written to the audit log.</p></div>
      <div class="grid g-3" style="margin-top:20px">
        <div class="card">
          <h3>Today</h3><p class="sub">Committed payments, including money held in escrow</p>
          ${m ? `<div class="row" style="font-size:13px;margin-bottom:8px"><span class="k">Spent today</span><span class="mono">${money(m.spentToday)} / ${money(m.dailyCap)}</span></div>
          <div class="progress ${pct >= 85 ? 'rose' : pct >= 50 ? 'amber' : ''}"><div style="width:${pct}%"></div></div>
          <div class="divider"></div>
          <div class="stack-sm" style="font-size:13px">
            <div class="row"><span class="k">Per-order cap</span><span class="mono">${money(m.perOrderCap)}</span></div>
            <div class="row"><span class="k">Valid until</span><span>${esc(m.validUntil)}</span></div>
            <div class="row"><span class="k">Order deadlines enforced</span><span>${m.enforceOrderDeadline ? 'Yes' : 'No'}</span></div>
            <div class="row"><span class="k">Version</span><span class="mono">v${m.version} · ${esc(m.updatedBy)} · ${when(m.updatedAt)}</span></div>
          </div>` : '<div class="empty">Create a mandate to allow payments.</div>'}
        </div>
        <div class="card span-2">
          <h3>${m ? 'Change mandate' : 'Create mandate'}</h3><p class="sub">Only you, signed in with a verified code, can change this.</p>
          <form id="mandate-form" class="stack">
            <div class="grid g-3" style="gap:12px">
              <div class="field"><label for="m-per">Per-order cap (R)</label><input id="m-per" class="input" name="perOrderCap" type="number" min="1" step="0.01" required value="${m ? Number(m.perOrderCap) : 2000}"></div>
              <div class="field"><label for="m-daily">Daily cap (R)</label><input id="m-daily" class="input" name="dailyCap" type="number" min="1" step="0.01" required value="${m ? Number(m.dailyCap) : 5000}"></div>
              <div class="field"><label for="m-until">Valid until</label><input id="m-until" class="input" name="validUntil" type="date" required value="${esc(until)}"></div>
            </div>
            <fieldset class="field" style="border:0;padding:0"><legend style="font-size:13px;font-weight:600;margin-bottom:6px">Approved supplier allow-list</legend>
              <div class="check-list">${data.suppliers.map((s) => `<label><input type="checkbox" name="allowed" value="${esc(s.id)}" ${allowed.has(s.id) ? 'checked' : ''} ${s.verified && !s.screeningHit ? '' : 'disabled'}>
                ${esc(s.name)} <small class="sub">${s.verified ? (s.onTrial ? 'verified · trial supplier (orders up to R3,000)' : 'verified') : 'not verified: cannot be paid'}${s.screeningHit ? ' · watch-list hit' : ''}</small></label>`).join('')}</div></fieldset>
            <label style="display:flex;gap:8px;align-items:center;font-size:14px"><input type="checkbox" name="enforceOrderDeadline" ${!m || m.enforceOrderDeadline ? 'checked' : ''}> Block payments after an order's approval deadline</label>
            <button class="btn btn-primary" type="submit">Save mandate (needs a verification code)</button>
          </form>
        </div>
      </div>`;

    root.querySelector('#mandate-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      const body = { perOrderCap: f.get('perOrderCap'), dailyCap: f.get('dailyCap'), validUntil: f.get('validUntil'), allowedSuppliers: f.getAll('allowed'), enforceOrderDeadline: f.get('enforceOrderDeadline') === 'on' };
      if (Number(body.perOrderCap) > Number(body.dailyCap)) return toast('Per-order cap cannot exceed the daily cap', 'error');
      const button = e.target.querySelector('button[type=submit]');
      button.disabled = true;
      try {
        const r = await withStepUp((stepUp) => api.saveMandate(body, stepUp));
        if (!r) return;
        data = await api.mandate();
        if (alive()) { draw(); toast(r.message); }
      } catch (err) { toast(err.message, 'error'); }
      finally { button.disabled = false; }
    });
  };
  draw();
}
