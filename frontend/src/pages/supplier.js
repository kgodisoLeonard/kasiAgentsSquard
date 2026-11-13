import { esc, money, toast, statusChip, when, simulatedBadge } from '../ui.js';

export async function renderSupplier(root, api, alive) {
  const [orderData, directoryData, priceData] = await Promise.all([api.groupOrders(), api.directory(), api.myPrices()]);
  if (!alive()) return;
  let orders = orderData.orders;
  let prices = priceData.prices;
  const spazas = directoryData.spazas;

  root.innerHTML = `
    <div class="hero"><span class="chip green">Verified supplier workspace</span> ${simulatedBadge('Ledger simulated')}
      <h3>Orders arrive already paid into escrow</h3>
      <p>You only see an order once the buyers' shares are in escrow and the minimum is met. Payment is released directly to your verified payout address when every paying buyer confirms delivery.</p></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Spaza owners in the network</h3><p class="sub">Registered shops looking for trusted supply partners.</p></div><span class="chip violet">${spazas.length} spazas</span></div>
      <div class="directory-grid">${spazas.map((s) => `<div class="directory-card"><div class="row"><b>${esc(s.businessName)}</b><span class="chip ${s.verified ? 'green' : 'amber'}">${s.verified ? 'Verified' : 'New owner'}</span></div><p class="sub">${esc(s.name)} · South Africa</p></div>`).join('') || '<div class="empty">No spaza owners are registered yet.</div>'}</div></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>My price list</h3><p class="sub">Wholesale price per unit. The agent orders at these prices, but never above a shop owner's own price limit.</p></div><span class="chip green" id="price-count"></span></div>
      <div id="price-list" class="price-list"></div>
      <form id="add-price" class="price-add"><input class="input" name="product" placeholder="Add a product, e.g. Sugar (2.5kg)" maxlength="120" required><input class="input" name="unitPrice" type="number" min="0.01" step="0.01" placeholder="Price (R)" required><button class="btn btn-primary btn-sm" type="submit">Add</button></form></div>
    <div id="orders" style="margin-top:20px"></div>`;

  const drawPrices = () => {
    root.querySelector('#price-count').textContent = `${prices.length} product${prices.length === 1 ? '' : 's'}`;
    root.querySelector('#price-list').innerHTML = prices.map((p) => `<form class="price-row" data-product="${esc(p.product)}"><span>${esc(p.product)}</span><input class="input mono" name="unitPrice" type="number" min="0.01" step="0.01" value="${Number(p.unitPrice).toFixed(2)}" aria-label="Price for ${esc(p.product)}"><button class="btn btn-ghost btn-sm" type="submit">Save</button></form>`).join('') || '<div class="empty">No products priced yet. Add your first one below.</div>';
  };
  const savePrice = async (product, unitPrice, button) => {
    button.disabled = true;
    try { const r = await api.setMyPrice(product, unitPrice); prices = r.prices; drawPrices(); toast(r.message); return true; }
    catch (err) { toast(err.message, 'error'); button.disabled = false; return false; }
  };
  drawPrices();
  root.querySelector('#price-list').onsubmit = (e) => { e.preventDefault(); const f = e.target; savePrice(f.dataset.product, Number(f.unitPrice.value), f.querySelector('button')); };
  root.querySelector('#add-price').onsubmit = async (e) => {
    e.preventDefault(); const f = e.target;
    if (await savePrice(f.product.value.trim(), Number(f.unitPrice.value), f.querySelector('button'))) f.reset();
  };

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
