import { esc, money, toast, statusChip, when, simulatedBadge, updatesList } from '../ui.js';
import { supplierDashboardHtml, wireTooltips } from './supplier-dashboard.js';

export async function renderSupplier(root, api, alive) {
  const [orderData, directoryData, priceData, updateData, dashData] = await Promise.all([api.groupOrders(), api.directory(), api.myPrices(), api.updates(), api.supplierDashboard()]);
  if (!alive()) return;
  let orders = orderData.orders;
  let prices = priceData.prices;
  const spazas = directoryData.spazas;

  root.innerHTML = `
    <div class="hero"><span class="chip green">Verified supplier workspace</span> ${simulatedBadge('Ledger simulated')}
      <h3>Orders arrive already paid into escrow</h3>
      <p>You only see an order once the buyers' shares are in escrow and the minimum is met. Payment is released directly to your verified payout address when every paying buyer confirms delivery.</p></div>
    <div id="supplier-dash" class="viz sd-root" style="margin-top:20px">${supplierDashboardHtml(dashData)}</div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Spaza owners in the network</h3><p class="sub">Registered shops looking for trusted supply partners.</p></div><span class="chip violet">${spazas.length} spazas</span></div>
      <div class="directory-grid">${spazas.map((s) => `<div class="directory-card"><div class="row"><b>${esc(s.businessName)}</b><span class="chip ${s.verified ? 'green' : 'amber'}">${s.verified ? 'Verified' : 'New owner'}</span></div><p class="sub">${esc(s.name)} · South Africa</p></div>`).join('') || '<div class="empty">No spaza owners are registered yet.</div>'}</div></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>My price list</h3><p class="sub">Wholesale price and stock per unit. The agent orders at these prices (never above a shop owner's own limit); orders bigger than your stock go to another supplier.</p></div><span class="chip green" id="price-count"></span></div>
      <div id="price-list" class="price-list"></div>
      <form id="add-price" class="price-add"><input class="input" name="product" placeholder="Add a product, e.g. Sugar (2.5kg)" maxlength="120" required><input class="input" name="unitPrice" type="number" min="0.01" step="0.01" placeholder="Price (R)" required><input class="input" name="stock" type="number" min="0" step="1" placeholder="Stock"><button class="btn btn-primary btn-sm" type="submit">Add</button></form></div>
    <div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Latest updates</h3><p class="sub">New paid orders, disputes and payouts. This page refreshes itself.</p></div><span class="chip green">● Live</span></div><div id="updates" class="stack-sm">${updatesList(updateData.updates)}</div></div>
    <div id="orders" style="margin-top:20px"></div>`;

  const drawPrices = () => {
    root.querySelector('#price-count').textContent = `${prices.length} product${prices.length === 1 ? '' : 's'}`;
    root.querySelector('#price-list').innerHTML = prices.map((p) => `<form class="price-row" data-product="${esc(p.product)}"><span>${esc(p.product)}${p.stock === 0 ? ' <span class="chip rose">Out of stock</span>' : ''}</span><label class="mini">R<input class="input mono" name="unitPrice" type="number" min="0.01" step="0.01" value="${Number(p.unitPrice).toFixed(2)}" aria-label="Price for ${esc(p.product)}"></label><label class="mini">Stock<input class="input mono" name="stock" type="number" min="0" step="1" value="${p.stock ?? ''}" placeholder="—" aria-label="Stock for ${esc(p.product)}"></label><button class="btn btn-ghost btn-sm" type="submit">Save</button><button class="btn btn-danger-ghost btn-sm" type="button" data-delete-item="${esc(p.product)}" aria-label="Delete ${esc(p.product)}">Delete</button></form>`).join('') || '<div class="empty">No products priced yet. Add your first one below.</div>';
  };
  const savePrice = async (product, unitPrice, stock, button) => {
    button.disabled = true;
    try { const r = await api.setMyPrice(product, unitPrice, stock === '' ? null : Number(stock)); prices = r.prices; drawPrices(); toast(r.message); return true; }
    catch (err) { toast(err.message, 'error'); button.disabled = false; return false; }
  };
  drawPrices();
  root.querySelector('#price-list').onsubmit = (e) => { e.preventDefault(); const f = e.target; savePrice(f.dataset.product, Number(f.unitPrice.value), f.stock.value, f.querySelector('button[type=submit]')); };
  root.querySelector('#price-list').onclick = async (e) => {
    const del = e.target.closest('[data-delete-item]');
    if (!del || !confirm(`Remove ${del.dataset.deleteItem} from your price list? Orders already placed are not affected.`)) return;
    del.disabled = true;
    try { const r = await api.deleteMyPrice(del.dataset.deleteItem); prices = r.prices; drawPrices(); toast(r.message); }
    catch (err) { toast(err.message, 'error'); del.disabled = false; }
  };
  root.querySelector('#add-price').onsubmit = async (e) => {
    e.preventDefault(); const f = e.target;
    if (await savePrice(f.product.value.trim(), Number(f.unitPrice.value), f.stock.value, f.querySelector('button'))) f.reset();
  };

  const next = { FUNDED: ['ACCEPT', 'Accept order'], ACCEPTED: ['DISPATCH', 'Mark dispatched'], IN_TRANSIT: ['DELIVER', 'Mark delivered'] };
  const draw = () => {
    root.querySelector('#orders').innerHTML = orders.map((o) => {
      const step = next[o.status];
      const confirmed = o.shares.filter((s) => s.deliveryConfirmed).length, paying = o.shares.filter((s) => ['FUNDED', 'RELEASED'].includes(s.status)).length;
      return `<div class="card order-card"><div class="card-head"><div><h3>${esc(o.product)} · ${o.fundedQuantity} units</h3><p class="sub">${esc(o.id)} · ${paying} buyer${paying === 1 ? '' : 's'} · deliver by ${when(o.deliveryDeadline)}</p></div>${statusChip(o.status)}</div>
        <div class="row"><span class="k">${o.status === 'COMPLETED' ? 'Paid to you' : 'Held in escrow for you'}</span><b class="mono">${money(o.status === 'COMPLETED' ? o.shares.filter((s) => s.status === 'RELEASED').reduce((a, s) => a + Number(s.total), 0) : o.heldInEscrow)}</b></div>
        <div class="row" style="margin-top:8px"><span class="k">Buyer confirmations</span><span>${confirmed}/${paying}</span></div>
        ${o.disputeReason ? `<div class="sim-note">Dispute opened: ${esc(o.disputeReason)}. A reviewer will contact you for delivery proof.</div>` : ''}
        <div class="order-actions-bar">${step ? `<button class="btn btn-primary btn-sm" data-a="${step[0]}" data-id="${esc(o.id)}">${step[1]}</button>` : ''}${o.status === 'FUNDED' ? `<button class="btn btn-danger-ghost btn-sm" data-decline="${esc(o.id)}">Decline</button>` : ''}
        ${o.status === 'DELIVERED_PENDING_CONFIRMATION' ? '<span class="chip amber">Waiting for buyers to confirm receipt</span>' : ''}</div></div>`;
    }).join('') || '<div class="card empty"><div class="big">📦</div>No funded orders yet.</div>';
  };
  draw();

  const dashEl = root.querySelector('#supplier-dash');
  wireTooltips(dashEl);
  let lastOrders = JSON.stringify(orders), lastUpdates = JSON.stringify(updateData.updates), lastDash = JSON.stringify(dashData);
  const timer = setInterval(async () => {
    if (!alive()) return clearInterval(timer);
    if (root.querySelector('#orders button:disabled')) return;
    try {
      const [o, u, d] = await Promise.all([api.groupOrders(), api.updates(), api.supplierDashboard()]);
      if (!alive()) return clearInterval(timer);
      if (JSON.stringify(d) !== lastDash) {
        lastDash = JSON.stringify(d);
        const open = dashEl.querySelector('details')?.open;
        dashEl.innerHTML = supplierDashboardHtml(d); wireTooltips(dashEl);
        if (open) dashEl.querySelector('details').open = true;
      }
      if (JSON.stringify(o.orders) !== lastOrders) { orders = o.orders; lastOrders = JSON.stringify(orders); draw(); }
      if (JSON.stringify(u.updates) !== lastUpdates) { lastUpdates = JSON.stringify(u.updates); root.querySelector('#updates').innerHTML = updatesList(u.updates); }
    } catch { /* try again next tick */ }
  }, 8000);

  root.querySelector('#orders').onclick = async (e) => {
    const d = e.target.closest('[data-decline]');
    if (d) {
      const reason = prompt('Why are you declining this order? The buyers are refunded and the order moves to another supplier.', 'Not enough stock');
      if (reason === null) return;
      d.disabled = true;
      try { const r = await api.supplierDecline(d.dataset.decline, reason); orders = orders.map((o) => (o.id === r.order.id ? r.order : o)); draw(); toast(r.message); }
      catch (err) { toast(err.message, 'error'); d.disabled = false; }
      return;
    }
    const b = e.target.closest('[data-a]');
    if (!b) return;
    b.disabled = true;
    try { const r = await api.supplierAction(b.dataset.id, b.dataset.a); orders = orders.map((o) => (o.id === r.order.id ? r.order : o)); draw(); toast('Order updated'); }
    catch (err) { toast(err.message, 'error'); b.disabled = false; }
  };
}
