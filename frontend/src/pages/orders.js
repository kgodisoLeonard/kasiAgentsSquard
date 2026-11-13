import { esc, money, toast, statusChip, when, simulatedBadge, updatesList } from '../ui.js';
import { withStepUp, session } from '../api.js';

export async function renderOrders(root, api, alive) {
  const me = session.user();
  const [orderData, offerData, memberData, updateData] = await Promise.all([api.groupOrders(), api.suppliers(), api.groupMembers(), api.updates()]);
  if (!alive()) return;
  let orders = orderData.orders;
  const offers = offerData.suppliers.filter((s) => s.verified);
  const others = memberData.members.filter((m) => m.id !== me.id);

  root.innerHTML = `
    <div class="hero"><span class="chip violet">Escrow protected</span> ${simulatedBadge('Ledger simulated')}
      <h3>Buy together, pay only your own share</h3>
      <p>Each member sees the same order, price and split, and approves only their own share. Your payment passes your spending mandate, then waits in escrow.
      It goes straight to the verified supplier once every paying buyer confirms delivery, and is refunded if the supplier minimum is not met or delivery is not confirmed in time.</p></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Latest updates</h3><p class="sub">Every shop in an order sees the same order and gets these updates. This page refreshes itself.</p></div><span class="chip green" id="live-chip">● Live</span></div><div id="updates" class="stack-sm"></div></div>
    <div class="grid g-3" style="margin-top:20px">
      <div class="span-2" id="order-list"></div>
      <div class="card" style="align-self:start"><h3>Start a group order</h3><p class="sub">You and the shops you invite each approve your own share.</p>
        <form id="new-order" class="stack">
          <div class="field"><label for="no-offer">Verified offer</label><select id="no-offer" class="input" name="offer">${offers.map((o, i) => `<option value="${i}">${esc(o.product)} · ${esc(o.name)} · ${money(o.price)} per unit (min ${o.minOrder})</option>`).join('')}</select></div>
          <div class="field"><label for="no-qty">My quantity (units)</label><input id="no-qty" class="input" name="qty" type="number" min="1" value="5" required></div>
          <fieldset class="field" style="border:0;padding:0"><legend style="font-size:13px;font-weight:600;margin-bottom:6px">Invite shops</legend>
            <div class="check-list">${others.map((m) => `<label><input type="checkbox" data-invite="${esc(m.id)}" ${m.verified ? 'checked' : ''}> ${esc(m.shop)} <small class="sub">(${esc(m.name)})</small> <input class="input" style="width:80px;padding:6px" type="number" min="1" value="5" data-invite-qty="${esc(m.id)}" aria-label="Quantity for ${esc(m.shop)}"></label>`).join('') || '<p class="sub">No other owners registered yet.</p>'}</div></fieldset>
          <div class="grid g-2" style="gap:12px">
            <div class="field"><label for="no-fee">Delivery fee (R)</label><input id="no-fee" class="input" name="fee" type="number" min="0" value="250"></div>
            <div class="field"><label for="no-min">Approval window (min)</label><input id="no-min" class="input" name="minutes" type="number" min="1" value="60"></div>
          </div>
          <button class="btn btn-primary btn-block" type="submit">Create order draft</button>
          <p class="sub">The supplier's minimum must be reached by the deadline or the order is cancelled and every payment refunded.</p>
        </form></div>
    </div>`;

  const $ = (s) => root.querySelector(s);

  const draw = () => {
    $('#order-list').innerHTML = orders.map((o) => {
      const mine = o.yourShare;
      const pct = Math.min(100, Math.round((o.fundedQuantity / o.minQuantity) * 100));
      const actions = [];
      if (o.status === 'COLLECTING' && mine && ['PENDING', 'BLOCKED'].includes(mine.status)) {
        actions.push(`<button class="btn btn-primary btn-sm" data-act="approve" data-id="${esc(o.id)}">Approve my share · ${money(mine.total)}</button>`);
        actions.push(`<button class="btn btn-ghost btn-sm" data-act="decline" data-id="${esc(o.id)}">Decline</button>`);
      }
      if (['IN_TRANSIT', 'DELIVERED_PENDING_CONFIRMATION'].includes(o.status) && mine?.status === 'FUNDED' && !mine.deliveryConfirmed)
        actions.push(`<button class="btn btn-primary btn-sm" data-act="confirm" data-id="${esc(o.id)}">Confirm I received my stock</button>`);
      if (['FUNDED', 'ACCEPTED', 'IN_TRANSIT', 'DELIVERED_PENDING_CONFIRMATION'].includes(o.status) && mine?.status === 'FUNDED')
        actions.push(`<button class="btn btn-ghost btn-sm" data-act="dispute" data-id="${esc(o.id)}">Report non-delivery</button>`);
      if (o.status === 'COMPLETED' && mine?.status === 'RELEASED')
        actions.push(`<button class="btn btn-ghost btn-sm" data-act="rate" data-id="${esc(o.id)}" data-supplier="${esc(o.supplierId)}">Rate supplier</button>`);
      actions.push(`<button class="btn btn-ghost btn-sm" data-tab="disputes" data-evidence="${esc(o.id)}">Evidence pack</button>`);
      return `<div class="card order-card">
        <div class="card-head"><div><h3>${esc(o.product)} · ${esc(o.supplierName)}</h3>
          <p class="sub">${esc(o.id)} · ${money(o.unitPrice)} per unit · delivery ${money(o.deliveryFee)} split by quantity · drafted by ${esc(o.draftedBy.replace('AGENT:', 'agent ').replace('HUMAN:', ''))}</p></div>${statusChip(o.status)}</div>
        <div class="row" style="font-size:13px"><span class="k">Supplier minimum</span><span>${o.fundedQuantity}/${o.minQuantity} units funded</span></div>
        <div class="progress ${pct < 100 ? 'amber' : ''}" style="margin:6px 0 12px"><div style="width:${pct}%"></div></div>
        <div class="row" style="font-size:13px"><span class="k">${o.status === 'COLLECTING' ? 'Approvals close' : 'Delivery must be confirmed by'}</span><span>${when(o.status === 'COLLECTING' ? o.approvalDeadline : o.deliveryDeadline)}</span></div>
        <div class="row" style="font-size:13px"><span class="k">Held in escrow</span><b class="mono">${money(o.heldInEscrow)}</b></div>
        <div class="table-wrap" style="margin-top:12px"><table class="table split-table"><thead><tr><th>Member</th><th>Qty</th><th>Goods</th><th>Delivery</th><th>Total</th><th>Status</th></tr></thead><tbody>
          ${o.shares.map((s) => `<tr${s.ownerId === me.id ? ' style="font-weight:600"' : ''}><td>${esc(s.ownerName)}${s.ownerId === me.id ? ' (you)' : ''}</td><td>${s.quantity}</td><td class="mono">${money(s.goods)}</td><td class="mono">${money(s.delivery)}</td><td class="mono">${money(s.total)}</td><td>${statusChip(s.status)}${s.deliveryConfirmed ? ' ✓ received' : ''}${s.blockedReason ? `<br><small style="color:var(--rose)">${esc(s.blockedReason)}</small>` : ''}${s.fundedBy && s.fundedBy.startsWith('AGENT') ? ' <small class="sub">🤖 by agent</small>' : ''}</td></tr>`).join('')}
          <tr><td><b>Total</b></td><td>${o.totalQuantity}</td><td></td><td class="mono">${money(o.deliveryFee)}</td><td class="mono"><b>${money(o.total)}</b></td><td></td></tr></tbody></table></div>
        ${/Redirected/.test(o.reason || '') ? `<div class="sim-note">↪ ${esc(o.reason)}</div>` : ''}
        ${o.riskFlags.length ? `<div class="sim-note">⚠ Fraud monitor flags (for review, not blocking): ${o.riskFlags.map(esc).join('; ')}</div>` : ''}
        ${o.disputeReason ? `<div class="sim-note">Dispute: ${esc(o.disputeReason)}. Escrow is held until a human reviewer decides.</div>` : ''}
        <div class="order-actions-bar">${actions.join('')}</div>
        <details style="margin-top:10px"><summary class="sub" style="cursor:pointer">Audit trail (${o.timeline.length})</summary><div class="timeline">${o.timeline.map((t) => `<div>#${t.auditSeq} · ${when(t.at)} · ${esc(t.actor)} · ${esc(t.action)}</div>`).join('')}</div></details>
      </div>`;
    }).join('') || '<div class="card empty"><div class="big">🧺</div>No group orders yet. Start one here, ask the Finance Agent to draft one, or order from the Marketplace.</div>';
  };
  draw();

  let updates = updateData.updates;
  const drawUpdates = () => { $('#updates').innerHTML = updatesList(updates); };
  drawUpdates();
  const refresh = async () => { orders = (await api.groupOrders()).orders; if (alive()) draw(); };
  // Live: pick up other shops' approvals, supplier steps and confirmations without reloading. Only redraws on change,
  // so open sections and buttons are not reset needlessly.
  let lastOrders = JSON.stringify(orders), lastUpdates = JSON.stringify(updates);
  const timer = setInterval(async () => {
    if (!alive()) return clearInterval(timer);
    if (root.querySelector('button:disabled')) return;   // an action is in progress
    try {
      const [o, u] = await Promise.all([api.groupOrders(), api.updates()]);
      if (!alive()) return clearInterval(timer);
      const so = JSON.stringify(o.orders), su = JSON.stringify(u.updates);
      if (so !== lastOrders) { orders = o.orders; lastOrders = so; draw(); }
      if (su !== lastUpdates) { updates = u.updates; lastUpdates = su; drawUpdates(); }
      $('#live-chip').textContent = '● Live';
    } catch { $('#live-chip').textContent = '○ Reconnecting'; }
  }, 8000);
  const replace = (order) => { orders = orders.map((o) => (o.id === order.id ? order : o)); draw(); };

  $('#order-list').addEventListener('click', async (e) => {
    const evidence = e.target.closest('[data-evidence]');
    if (evidence) { sessionStorage.setItem('spaza_evidence', evidence.dataset.evidence); return; }
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const id = b.dataset.id;
    b.disabled = true;
    try {
      if (b.dataset.act === 'approve') {
        const r = await withStepUp((stepUp) => api.approveShare(id, stepUp));
        if (r) { replace(r.order); toast(r.message, r.success ? 'success' : 'error'); }
      } else if (b.dataset.act === 'decline') {
        replace((await api.declineShare(id)).order); toast('You declined your share');
      } else if (b.dataset.act === 'confirm') {
        const r = await api.confirmDelivery(id); replace(r.order); toast(r.message);
      } else if (b.dataset.act === 'dispute') {
        const reason = window.prompt('What went wrong with the delivery?', 'Stock not received');
        if (reason === null) return;
        const r = await api.reportNonDelivery(id, reason); replace(r.order); toast(r.message);
      } else if (b.dataset.act === 'rate') {
        const rating = Number(window.prompt('Rate this supplier from 1 to 5 stars', '5'));
        if (!rating) return;
        const comment = window.prompt('Optional comment about delivery and service', '') || '';
        await api.rateSupplier(b.dataset.supplier, { orderId: id, rating, comment }); toast('Supplier rating saved');
      }
    } catch (err) { toast(err.message, 'error'); await refresh(); }
    finally { b.disabled = false; }
  });

  $('#new-order').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    const offer = offers[Number(f.get('offer'))];
    if (!offer) return toast('No verified offers available', 'error');
    const invite = [...root.querySelectorAll('[data-invite]:checked')].map((c) => ({ ownerId: c.dataset.invite, quantity: Number(root.querySelector(`[data-invite-qty="${CSS.escape(c.dataset.invite)}"]`).value) }));
    try {
      const r = await api.createGroupOrder({ supplierId: offer.id, product: offer.product, unitPrice: offer.price, minQuantity: offer.minOrder, quantity: Number(f.get('qty')), deliveryFee: Number(f.get('fee')), approvalMinutes: Number(f.get('minutes')), invite });
      orders = [r.order, ...orders]; draw();
      toast('Draft created. Approve your own share to pay into escrow.');
    } catch (err) { toast(err.message, 'error'); }
  });
}
