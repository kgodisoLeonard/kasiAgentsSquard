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
      </div>
      ${m ? autonomyCard(m) : ''}`;

    if (m) wireAutonomy();

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
  /** Automatic restock rules: when stock drops below the level, the agent orders this quantity from this supplier. */
  function autonomyCard(m) {
    const rules = new Map((m.restockRules || []).map((r) => [r.product.toLowerCase(), r]));
    const allowed = new Set(m.allowedSuppliers || []);
    const names = new Map(data.suppliers.map((s) => [s.id, s.name]));
    const rows = data.inventory.map((item) => {
      const offers = data.catalog.filter((c) => c.product.toLowerCase() === item.name.toLowerCase() && allowed.has(c.supplierId));
      const r = rules.get(item.name.toLowerCase());
      if (!offers.length && !r) return '';
      const cheapest = offers.slice().sort((a, b) => a.unitPrice - b.unitPrice)[0];
      const priceOf = (id) => offers.find((o) => o.supplierId === id)?.unitPrice;
      return `<tr data-product="${esc(item.name)}">
        <td><label style="display:flex;gap:8px;align-items:center"><input type="checkbox" data-f="on" ${r ? 'checked' : ''} aria-label="Restock ${esc(item.name)} automatically"> ${esc(item.name)}</label></td>
        <td class="mono">${item.qty}</td>
        <td><input class="input" style="width:70px" type="number" min="1" data-f="reorderBelow" value="${r ? r.reorderBelow : 5}" aria-label="Reorder below for ${esc(item.name)}"></td>
        <td><input class="input" style="width:80px" type="number" min="1" data-f="orderQuantity" value="${r ? r.orderQuantity : 12}" aria-label="Order quantity for ${esc(item.name)}"></td>
        <td><select class="input" data-f="supplierId" aria-label="Supplier for ${esc(item.name)}">${offers.map((o) => `<option value="${esc(o.supplierId)}" ${(r ? r.supplierId : cheapest?.supplierId) === o.supplierId ? 'selected' : ''}>${esc(names.get(o.supplierId) || o.supplierId)} · ${money(o.unitPrice)}</option>`).join('') || '<option value="">No approved supplier sells this</option>'}</select></td>
        <td><input class="input" style="width:90px" type="number" min="0.01" step="0.01" data-f="maxUnitPrice" value="${r ? Number(r.maxUnitPrice) : Math.ceil(Number(cheapest?.unitPrice || priceOf(r?.supplierId) || 1) * 1.15)}" aria-label="Maximum unit price for ${esc(item.name)}"></td>
      </tr>`;
    }).join('');
    const spent = Number(m.agentSpentThisMonth || 0), budget = Number(m.monthlyAgentBudget || 0);
    return `<div class="card" style="margin-top:20px">
      <div class="card-head"><div><h3>Automatic restock (agent)</h3><p class="sub">When a sale at the till takes an item below its level, the Finance Agent orders exactly this quantity from this supplier, on its own.
        The order still passes every mandate check, is paid into escrow, and is only released to the supplier after you confirm delivery.</p></div>
        <span class="chip ${budget > 0 ? 'green' : 'amber'}">${budget > 0 ? 'Autonomous' : 'Off'}</span></div>
      <form id="autonomy-form" class="stack">
        <div class="grid g-3" style="gap:12px;align-items:end">
          <div class="field"><label for="a-budget">Agent budget per month (R)</label><input id="a-budget" class="input" type="number" min="0" step="0.01" value="${budget}"></div>
          <div class="row" style="font-size:13px"><span class="k">Agent spent this month</span><span class="mono">${money(spent)} / ${money(budget)}</span></div>
        </div>
        <div class="table-wrap"><table class="table"><thead><tr><th>Product</th><th>Stock</th><th>Reorder below</th><th>Order qty</th><th>Approved supplier</th><th>Max unit price (R)</th></tr></thead>
          <tbody>${rows || '<tr><td colspan="6" class="empty">None of your approved suppliers sell your stock items yet.</td></tr>'}</tbody></table></div>
        <button class="btn btn-primary" type="submit">Save restock rules (needs a verification code)</button>
      </form></div>`;
  }

  function wireAutonomy() {
    root.querySelector('#autonomy-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const restockRules = [...root.querySelectorAll('#autonomy-form tr[data-product]')].filter((tr) => tr.querySelector('[data-f="on"]').checked).map((tr) => ({
        product: tr.dataset.product,
        reorderBelow: Number(tr.querySelector('[data-f="reorderBelow"]').value),
        orderQuantity: Number(tr.querySelector('[data-f="orderQuantity"]').value),
        supplierId: tr.querySelector('[data-f="supplierId"]').value,
        maxUnitPrice: Number(tr.querySelector('[data-f="maxUnitPrice"]').value),
      }));
      if (restockRules.some((r) => !r.supplierId)) return toast('Choose an approved supplier for every selected product', 'error');
      const button = e.target.querySelector('button[type=submit]');
      button.disabled = true;
      try {
        const r = await withStepUp((stepUp) => api.saveAutonomy({ monthlyAgentBudget: Number(root.querySelector('#a-budget').value || 0), restockRules }, stepUp));
        if (!r) return;
        toast(r.message);
        for (const x of r.restock || []) {
          const res = x.result || {};
          if (res.status === 'ORDERED') toast(`Agent ordered ${res.quantity} × ${res.product} (${money(res.total)}, in escrow)`);
          else if (res.status === 'BLOCKED' || res.status === 'ESCALATED') toast(`${res.product}: ${res.reason}`, 'error');
        }
        data = await api.mandate();
        if (alive()) draw();
      } catch (err) { toast(err.message, 'error'); }
      finally { button.disabled = false; }
    });
  }

  draw();
}
