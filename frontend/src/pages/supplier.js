import { esc, money, toast, statusChip, when, simulatedBadge } from '../ui.js';

export async function renderSupplier(root, api, alive) {
  const [orderData, directoryData] = await Promise.all([api.groupOrders(), api.directory()]);
  if (!alive()) return;
  let orders = orderData.orders;
  const spazas = directoryData.spazas;

  root.innerHTML = `
    <div class="hero"><span class="chip green">Verified supplier workspace</span> ${simulatedBadge('Ledger simulated')}
      <h3>Orders arrive already paid into escrow</h3>
      <p>You only see an order once the buyers' shares are in escrow and the minimum is met. Payment is released directly to your verified payout address when every paying buyer confirms delivery.</p></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Spaza owners in the network</h3><p class="sub">Registered shops looking for trusted supply partners.</p></div><span class="chip violet">${spazas.length} spazas</span></div>
      <div class="directory-grid">${spazas.map((s) => `<div class="directory-card"><div class="row"><b>${esc(s.businessName)}</b><span class="chip ${s.verified ? 'green' : 'amber'}">${s.verified ? 'Verified' : 'New owner'}</span></div><p class="sub">${esc(s.name)} · South Africa</p></div>`).join('') || '<div class="empty">No spaza owners are registered yet.</div>'}</div></div>
    <div id="orders" style="margin-top:20px"></div>`;

  const next = { FUNDED: ['ACCEPT', 'Accept order'], ACCEPTED: ['DISPATCH', 'Mark dispatched'], IN_TRANSIT: ['DELIVER', 'Mark delivered'] };
  const draw = () => {
    root.querySelector('#orders').innerHTML = orders.map((o) => {
      const step = next[o.status];
      const confirmed = o.shares.filter((s) => s.deliveryConfirmed).length, paying = o.shares.filter((s) => ['FUNDED', 'RELEASED'].includes(s.status)).length;
      return `<div class="card order-card"><div class="card-head"><div><h3>${esc(o.product)} · ${o.fundedQuantity} kg</h3><p class="sub">${esc(o.id)} · ${paying} buyer${paying === 1 ? '' : 's'} · deliver by ${when(o.deliveryDeadline)}</p></div>${statusChip(o.status)}</div>
        <div class="row"><span class="k">${o.status === 'COMPLETED' ? 'Paid to you' : 'Held in escrow for you'}</span><b class="mono">${money(o.status === 'COMPLETED' ? o.shares.filter((s) => s.status === 'RELEASED').reduce((a, s) => a + Number(s.total), 0) : o.heldInEscrow)}</b></div>
        <div class="row" style="margin-top:8px"><span class="k">Buyer confirmations</span><span>${confirmed}/${paying}</span></div>
        ${o.disputeReason ? `<div class="sim-note">Dispute opened: ${esc(o.disputeReason)}. A reviewer will contact you for delivery proof.</div>` : ''}
        <div class="order-actions-bar">${step ? `<button class="btn btn-primary btn-sm" data-a="${step[0]}" data-id="${esc(o.id)}">${step[1]}</button>` : ''}
        ${o.status === 'DELIVERED_PENDING_CONFIRMATION' ? '<span class="chip amber">Waiting for buyers to confirm receipt</span>' : ''}</div></div>`;
    }).join('') || '<div class="card empty"><div class="big">📦</div>No funded orders yet.</div>';
  };
  draw();

  root.querySelector('#orders').onclick = async (e) => {
    const b = e.target.closest('[data-a]');
    if (!b) return;
    b.disabled = true;
    try { const r = await api.supplierAction(b.dataset.id, b.dataset.a); orders = orders.map((o) => (o.id === r.order.id ? r.order : o)); draw(); toast('Order updated'); }
    catch (err) { toast(err.message, 'error'); b.disabled = false; }
  };
}
