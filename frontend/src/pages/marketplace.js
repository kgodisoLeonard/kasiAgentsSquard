import { esc, money, toast, productThumb, statusChip } from '../ui.js';
import { session, withStepUp } from '../api.js';

export async function renderMarketplace(root, api, alive) {
  const owner = session.user();
  const [supplierData, orderData, directoryData, mineData, recData] = await Promise.all([api.suppliers(), api.groupOrders(), api.directory(), api.ownerSuppliers(owner.id), api.recommendedSuppliers()]);
  if (!alive()) return;
  const offers = supplierData.suppliers.filter(s => s.verified);
  let suppliers = directoryData.suppliers;
  let mySuppliers = mineData.suppliers;
  const basket = new Map();
  root.innerHTML = `<div class="hero"><span class="chip violet">Verified marketplace</span><h3>Buy stock from trusted suppliers</h3><p>Select products, choose quantities, and place an order. Your payment passes your spending mandate, then waits in escrow until you confirm delivery.</p></div><div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Recommended for your shop</h3><p class="sub">${esc(recData.basis)}</p></div><span class="chip violet">Best first</span></div><div id="recommended" class="directory-grid"></div></div><div class="card" style="margin-top:20px"><div class="card-head"><div><h3>Suppliers in the network</h3><p class="sub">Compare verified suppliers and owner ratings before ordering.</p></div><span class="chip green">${suppliers.length} suppliers</span></div><div id="supplier-directory" class="directory-grid"></div></div><div class="grid g-3" style="margin-top:20px"><div class="card span-2"><div class="card-head"><div><h3>Supplier products</h3><p class="sub">Verified offers from every supplier's price list</p></div><span class="chip green">${offers.length} offers</span></div><input id="offer-search" class="input" placeholder="Search products or suppliers…" style="margin-bottom:12px"><div id="offers" class="products"></div></div><aside class="market-side"><div class="card"><div class="card-head"><div><h3>My suppliers</h3><p class="sub" id="my-suppliers-count">0 stores</p></div></div><form id="add-supplier-form" class="add-supplier"><label class="sr-only" for="add-supplier-select">Supplier store to add</label><select id="add-supplier-select" class="input"></select><button id="add-supplier-btn" class="btn btn-primary btn-sm" type="submit">Add</button></form><div id="my-suppliers" class="stack-sm my-suppliers"></div></div><div class="card"><div class="card-head"><div><h3>Your order</h3><p class="sub" id="basket-count">0 products</p></div><button id="clear-basket" class="btn btn-ghost btn-sm">Clear</button></div><div id="basket" class="stack-sm"></div><div class="divider"></div><div class="row"><span class="k">Total</span><b id="basket-total" class="mono">R0.00</b></div><button id="place-order" class="btn btn-primary btn-block" style="margin-top:16px">Place order</button></div></aside></div><div class="card" style="margin-top:20px"><h3>Recent marketplace orders</h3><p class="sub">Track deliveries and rate suppliers after receipt confirmation.</p><div id="recent-orders" class="stack-sm"></div></div>`;
  const $ = selector => root.querySelector(selector);
  const offerKey = s => `${s.id}:${s.product}`;
  const stars = rating => rating ? `${'★'.repeat(Math.round(rating))}${'☆'.repeat(5 - Math.round(rating))}` : 'No ratings yet';
  const isMine = id => mySuppliers.some(s => s.id === id);
  const drawMySuppliers = () => {
    const available = suppliers.filter(s => !isMine(s.id));
    const select = $('#add-supplier-select');
    const chosen = select.value;
    select.innerHTML =available.length
      ? `<option value="">Select a supplier store…</option>${available.map(s => `<option value="${esc(s.id)}">${esc(s.businessName || s.name)}${s.verified ? '' : ' (pending verification)'}</option>`).join('')}`
      : '<option value="">All registered suppliers added</option>';
    if (available.some(s => s.id === chosen)) select.value = chosen;
    select.disabled = !available.length;
    $('#add-supplier-btn').disabled = !available.length;
    $('#my-suppliers-count').textContent = `${mySuppliers.length} store${mySuppliers.length === 1 ? '' : 's'}`;
    $('#my-suppliers').innerHTML = mySuppliers.map(s => `<div class="row my-supplier"><span><b>${esc(s.businessName || s.name)}</b><br><small>${s.rating ? `★ ${s.rating}/5` : 'No ratings yet'} · ${s.verified ? 'Verified' : 'Pending'}</small></span><button class="btn btn-ghost btn-sm" type="button" data-remove-supplier="${esc(s.id)}" aria-label="Remove ${esc(s.businessName || s.name)}">Remove</button></div>`).join('') || '<div class="empty">No suppliers added yet. Pick a registered store above.</div>';
  };
  const drawSummary = () => {
    const items = [...basket.entries()].map(([key, qty]) => ({ offer: offers.find(s => offerKey(s) === key), qty }));
    $('#basket').innerHTML = items.length ? items.map(({ offer, qty }) => `<div class="row"><span>${esc(offer.product)}<br><small>${qty} units · ${money(offer.price)} each</small></span><b class="mono">${money(qty * offer.price)}</b></div>`).join('') : '<div class="empty">Select products to start an order.</div>';
    $('#basket-count').textContent = `${items.length} product${items.length === 1 ? '' : 's'}`;
    $('#basket-total').textContent = money(items.reduce((sum, x) => sum + x.qty * x.offer.price, 0));
  };
  const drawRecommended = () => {
    $('#recommended').innerHTML = recData.suppliers.map((s, i) => `<div class="directory-card${i === 0 ? ' top-pick' : ''}"><div class="row"><b>${i === 0 ? '🏆 ' : ''}${esc(s.name)}</b><span class="chip ${i === 0 ? 'green' : 'violet'}" title="Recommendation score out of 100">${s.score}</span></div><div class="rating-stars">${stars(s.rating)} <small>${s.ratingCount ? `${s.rating}/5 (${s.ratingCount})` : ''}</small></div><ul class="rec-reasons">${s.reasons.map(r => `<li>${esc(r)}</li>`).join('')}</ul>${s.latestReviews.length ? `<p class="sub rec-review">“${esc(s.latestReviews[0].comment || 'No comment')}” — ${esc(s.latestReviews[0].ownerName)}</p>` : ''}${isMine(s.id) ? '<span class="chip violet">In my suppliers</span>' : `<button class="btn btn-ghost btn-sm" type="button" data-add-recommended="${esc(s.id)}">Add to my suppliers</button>`}</div>`).join('') || '<div class="empty">No verified suppliers yet.</div>';
  };
  const draw = () => {
    drawRecommended();
    drawMySuppliers();
    $('#supplier-directory').innerHTML = suppliers.map(s => `<div class="directory-card"><div class="row"><b>${esc(s.businessName || s.name)}</b><span class="chip ${s.verified ? 'green' : 'amber'}">${s.verified ? 'Verified' : 'Pending'}</span></div>${isMine(s.id) ? '<span class="chip violet" style="margin-top:8px">In my suppliers</span>' : ''}<div class="rating-stars">${stars(s.rating)} <small>${s.rating ? `${s.rating}/5` : ''} (${s.ratingCount || 0})</small></div><p class="sub">${esc(s.country || 'South Africa')} · ${s.deliveryDays || 3} day delivery</p></div>`).join('') || '<div class="empty">No suppliers registered yet.</div>';
    const q = ($('#offer-search')?.value || '').toLowerCase();
    $('#offers').innerHTML = offers.filter(s => !q || s.product.toLowerCase().includes(q) || s.name.toLowerCase().includes(q)).map(s => { const key = offerKey(s), selected = basket.get(key); return `<div class="product ${selected ? 'low' : ''}" data-offer="${esc(key)}">${productThumb(s.product, '')}<div class="name">${esc(s.product)}</div><div class="meta">${esc(s.name)} · ${esc(s.country)}</div><div class="rating-stars">${stars(s.rating)} <small>${s.rating || 'New supplier'}</small></div><div class="foot"><span class="price">${money(s.price)}</span><span class="stock">Min ${s.minOrder}</span></div><div class="field" style="margin-top:8px"><label>Quantity (units)</label><input class="input" data-qty="${esc(key)}" type="number" min="${s.minOrder}" value="${selected || s.minOrder}"></div><button class="btn ${selected ? 'btn-primary' : 'btn-ghost'} btn-block btn-sm" data-select="${esc(key)}">${selected ? 'Selected' : 'Select product'}</button></div>`; }).join('');
    drawSummary();
    $('#recent-orders').innerHTML = orderData.orders.slice(0, 6).map(o => `<div class="row order-row"><span><b>${esc(o.product)}</b><br><small>${esc(o.id)} · ${o.totalQuantity} units · ${esc(o.supplierName)}${o.yourShare ? ` · your share ${money(o.yourShare.total)}` : ''}</small></span><span class="order-actions">${statusChip(o.status)}<button class="btn btn-ghost btn-sm" data-tab="orders">Open</button></span></div>`).join('') || '<div class="empty">No orders yet.</div>';
  };
  draw();
  $('#offers').onclick = e => { const select = e.target.closest('[data-select]'); if (!select) return; const key = select.dataset.select, offer = offers.find(s => offerKey(s) === key), input = root.querySelector(`[data-qty="${CSS.escape(key)}"]`), qty = Math.max(offer.minOrder, Number(input.value) || offer.minOrder); basket.has(key) ? basket.delete(key) : basket.set(key, qty); draw(); };
  $('#offers').oninput = e => { const input = e.target.closest('[data-qty]'); if (input && basket.has(input.dataset.qty)) { basket.set(input.dataset.qty, Math.max(1, Number(input.value) || 1)); drawSummary(); } };

  $('#recommended').onclick = async e => {
    const button = e.target.closest('[data-add-recommended]');
    if (!button) return;
    button.disabled = true;
    try { const r = await api.addOwnerSupplier(owner.id, button.dataset.addRecommended); mySuppliers = r.suppliers; toast(r.message); draw(); }
    catch (err) { toast(err.message, 'error'); button.disabled = false; }
  };

  $('#offer-search').oninput = () => draw();

  $('#add-supplier-form').onsubmit = async e => {
    e.preventDefault();
    const supplierId = $('#add-supplier-select').value;
    if (!supplierId) return toast('Select a supplier store to add', 'error');
    const button = $('#add-supplier-btn');
    button.disabled = true;
    try { const r = await api.addOwnerSupplier(owner.id, supplierId); mySuppliers = r.suppliers; toast(r.message); draw(); }
    catch (err) { toast(err.message, 'error'); button.disabled = false; }
  };
  $('#my-suppliers').onclick = async e => {
    const button = e.target.closest('[data-remove-supplier]');
    if (!button) return;
    button.disabled = true;
    try { const r = await api.removeOwnerSupplier(owner.id, button.dataset.removeSupplier); mySuppliers = r.suppliers; toast(r.message); draw(); }
    catch (err) { toast(err.message, 'error'); button.disabled = false; }
  };
  $('#clear-basket').onclick =() => { basket.clear(); draw(); };
  $('#place-order').onclick = async () => {
    const items = [...basket.entries()].map(([key, qty]) => ({ offer: offers.find(s => offerKey(s) === key), qty }));
    if (!items.length) return toast('Select at least one product', 'error');
    const tooSmall = items.find(({ offer, qty }) => qty < offer.minOrder);
    if (tooSmall) return toast(`${tooSmall.offer.product}: the supplier minimum is ${tooSmall.offer.minOrder} units. Start a group order to share it with other shops.`, 'error');
    const button = $('#place-order'); button.disabled = true; button.textContent = 'Checking mandate...';
    try {
      for (const { offer, qty } of items) {
        // Each item becomes an order with you as the only member; approving your share pays it into escrow after the mandate check.
        const draft = await api.createGroupOrder({ supplierId: offer.id, product: offer.product, unitPrice: offer.price, quantity: qty, minQuantity: offer.minOrder, deliveryFee: 0, approvalMinutes: 60 });
        const r = await withStepUp((stepUp) => api.approveShare(draft.order.id, stepUp));
        if (!r) { toast(`${offer.product}: draft saved under Group Orders (verification cancelled)`, 'error'); continue; }
        toast(`${offer.product}: ${r.message}`, r.success ? 'success' : 'error');
      }
      basket.clear();
      orderData.orders = (await api.groupOrders()).orders; draw();
    } catch (err) { toast(err.message, 'error'); }
    finally { button.disabled = false; button.textContent = 'Place order'; }
  };
}
