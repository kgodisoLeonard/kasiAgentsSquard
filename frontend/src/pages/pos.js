import { esc, money, toast, icons, emojiFor } from '../ui.js';

/**
 * Till. A customer's order is an open order on the server: adding an item takes it off the shelf immediately
 * (addToOrder), removing it or cancelling puts it back, and taking payment closes the order. When stock drops below
 * an owner-set restock rule, the Finance Agent reorders automatically within the mandate.
 */
export async function renderPOS(root, api, alive) {
  const [data, openData] = await Promise.all([api.inventory(), api.openCustomerOrder()]);
  if (!alive()) return;
  let inventory = data.inventory;
  let order = openData.order || null; // { id, lines:[{productId,name,category,qty,unitPrice,lineTotal}], subtotal, profit }
  let method = 'cash';
  let filter = '';
  let busy = false;

  root.innerHTML = `
    <div class="grid g-3">
      <div class="card span-2">
        <div class="card-head">
          <div><h3>Products</h3><p class="sub">Tap a product or type a barcode and press Enter. Items leave stock as soon as they are added.</p></div>
          <button class="btn btn-ghost btn-sm" id="add-product">+ New product</button>
        </div>
        <div class="search">${icons.search}<input id="search" class="input" placeholder="Search name or scan barcode…" autocomplete="off"/></div>
        <div id="catalog" class="products"></div>
      </div>

      <div class="card" style="align-self:start;position:sticky;top:20px">
        <div class="card-head"><div><h3>Customer order</h3><p class="sub" id="cart-count">No open order</p></div>
          <button class="btn btn-ghost btn-sm" id="clear">Cancel order</button></div>
        <div id="cart" class="cart-list"></div>
        <div class="divider"></div>
        <div class="stack-sm">
          <div class="row"><span class="k">Subtotal</span><span class="mono" id="subtotal">R0.00</span></div>
          <div class="row"><span class="k">Est. profit</span><span class="mono" style="color:var(--primary)" id="profit">R0.00</span></div>
        </div>
        <div class="divider"></div>
        <div class="row total-row"><span>Total</span><span class="v" id="total">R0.00</span></div>
        <div class="segmented" id="methods" style="margin-top:16px">
          <button data-m="cash" class="active">💵 Cash</button><button data-m="card">💳 Card</button><button data-m="rlusd">🪙 RLUSD</button>
        </div>
        <div id="cash-box" style="margin-top:12px">
          <div class="field"><label for="cash">Cash tendered</label><input id="cash" type="number" min="0" step="0.5" class="input" placeholder="0.00"/></div>
          <div class="row" style="margin-top:8px;font-size:13px"><span class="k">Change due</span><span class="mono" id="change" style="color:var(--primary)">R0.00</span></div>
        </div>
        <button id="complete" class="btn btn-primary btn-block" style="margin-top:16px;padding:14px">Take payment &amp; close order</button>
        <div id="agent-log" class="stack-sm" style="margin-top:14px"></div>
      </div>
    </div>`;

  const $ = (s) => root.querySelector(s);
  const byId = (id) => inventory.find((p) => p.id === id);
  const subtotal = () => Number(order?.subtotal || 0);

  function drawCatalog() {
    const f = filter.toLowerCase();
    const list = inventory.filter((p) => p.name.toLowerCase().includes(f) || p.barcode.includes(f) || (p.category || '').toLowerCase().includes(f));
    $('#catalog').innerHTML = list.map((p) => `
      <div class="product ${p.qty <= p.lowStock ? 'low' : ''} ${p.qty <= 0 ? 'out' : ''}" data-id="${esc(p.id)}" title="Click to update price">
        <div class="emoji">${emojiFor(p.category)}</div>
        <div class="name">${esc(p.name)}</div>
        <div class="meta">${esc(p.barcode)}</div>
        <div class="foot"><span class="price">${money(p.price)}</span><span class="stock">${p.qty <= 0 ? 'Sold out' : p.qty + ' left'}</span></div>
        <div style="display:flex;gap:6px;margin-top:8px"><button type="button" class="btn btn-ghost btn-sm" data-edit-price="${esc(p.id)}">Update price</button><button type="button" class="btn btn-primary btn-sm" data-add="${esc(p.id)}" ${p.qty <= 0 ? 'disabled' : ''}>Add</button></div>
      </div>`).join('') || `<div class="empty" style="grid-column:1/-1"><div class="big">🔍</div>No products match “${esc(filter)}”</div>`;
  }

  function drawCart() {
    const lines = order?.lines || [];
    const count = lines.reduce((n, l) => n + l.qty, 0);
    $('#cart').innerHTML = lines.length ? lines.map((l) => `
      <div class="cart-item">
        <div style="font-size:22px">${emojiFor(l.category)}</div>
        <div class="info"><b>${esc(l.name)}</b><span>${money(l.unitPrice)} each</span></div>
        <div class="qty"><button data-dec="${esc(l.productId)}" aria-label="Remove one ${esc(l.name)}">−</button><span>${l.qty}</span><button data-inc="${esc(l.productId)}" aria-label="Add one ${esc(l.name)}">+</button></div>
      </div>`).join('') : `<div class="empty"><div class="big">🛒</div>No items yet</div>`;
    $('#cart-count').textContent = order ? `${order.id} · ${count} item${count === 1 ? '' : 's'}` : 'No open order';
    $('#subtotal').textContent = money(subtotal());
    $('#profit').textContent = money(order?.profit || 0);
    $('#total').textContent = money(subtotal());
    updateChange();
    drawCatalog();
  }

  function updateChange() {
    const t = parseFloat($('#cash').value) || 0;
    $('#change').textContent = money(Math.max(0, t - subtotal()));
  }

  /** Shows what the agent did after a sale dropped stock below a restock rule. */
  function showRestock(r) {
    if (!r) return;
    const res = r.result || {};
    let text;
    if (!r.allowed) text = `Agent could not act: ${r.reason}`;
    else if (res.status === 'ORDERED') text = `🤖 Agent ordered ${res.quantity} × ${res.product} (${money(res.total)}, held in escrow) · ${res.orderId}`;
    else if (res.status === 'BLOCKED') text = `🤖 Restock for ${res.product} blocked by your mandate: ${res.reason}`;
    else if (res.status === 'ESCALATED') text = `🤖 ${res.reason}`;
    else if (res.code === 'ORDER_ALREADY_OPEN') text = `🤖 ${res.product}: restock order ${res.orderId} is already open`;
    else return;
    $('#agent-log').insertAdjacentHTML('afterbegin', `<div class="sim-note" style="margin-top:0">${esc(text)}</div>`);
    if (res.status === 'ORDERED' || res.status === 'BLOCKED' || res.status === 'ESCALATED') toast(text.replace('🤖 ', ''), res.status === 'ORDERED' ? 'success' : 'error');
  }

  async function ensureOrder() {
    if (!order) order = (await api.newCustomerOrder()).order;
    return order;
  }

  async function run(fn) {
    if (busy) return; busy = true;
    try { await fn(); } catch (err) { toast(err.message, 'error'); inventory = (await api.inventory()).inventory; }
    finally { busy = false; drawCart(); }
  }

  const add = (id) => run(async () => {
    const o = await ensureOrder();
    const r = await api.addToOrder(o.id, id, 1);
    order = r.order;
    inventory = inventory.map((p) => (p.id === r.item.id ? r.item : p));
    showRestock(r.restock);
  });

  const remove = (id) => run(async () => {
    order = (await api.removeFromOrder(order.id, id, 1)).order;
    inventory = (await api.inventory()).inventory;
  });

  $('#catalog').addEventListener('click', (e) => { const edit = e.target.closest('[data-edit-price]'); if (edit) return openPriceModal(api, byId(edit.dataset.editPrice), async () => { inventory = (await api.inventory()).inventory; drawCart(); }); const addButton = e.target.closest('[data-add]'); if (addButton) return add(addButton.dataset.add); const card = e.target.closest('[data-id]'); if (card && !e.target.closest('button')) openPriceModal(api, byId(card.dataset.id), async () => { inventory = (await api.inventory()).inventory; drawCart(); }); });
  $('#cart').addEventListener('click', (e) => {
    const inc = e.target.closest('[data-inc]'); const dec = e.target.closest('[data-dec]');
    if (inc) add(inc.dataset.inc);
    if (dec) remove(dec.dataset.dec);
  });
  $('#search').addEventListener('input', (e) => { filter = e.target.value.trim(); drawCatalog(); });
  $('#search').addEventListener('keydown', async (e) => {
    if (e.key !== 'Enter' || !/^\d{6,14}$/.test(filter)) return;
    try { const r = await api.inventoryByBarcode(filter); await add(r.item.id); e.target.value = ''; filter = ''; drawCatalog(); toast(`Scanned ${r.item.name}`); }
    catch (err) { toast(err.message, 'error'); }
  });
  $('#clear').addEventListener('click', () => run(async () => {
    if (!order) return;
    await api.cancelCustomerOrder(order.id);
    order = null; inventory = (await api.inventory()).inventory;
    toast('Order cancelled. Items returned to stock.');
  }));
  $('#cash').addEventListener('input', updateChange);
  $('#methods').addEventListener('click', (e) => {
    const b = e.target.closest('[data-m]'); if (!b) return;
    method = b.dataset.m;
    root.querySelectorAll('#methods button').forEach((x) => x.classList.toggle('active', x === b));
    $('#cash-box').style.display = method === 'cash' ? '' : 'none';
  });

  $('#complete').addEventListener('click', () => {
    if (!order?.lines?.length) return toast('Add items to the order first', 'error');
    const tendered = parseFloat($('#cash').value) || 0;
    if (method === 'cash' && tendered < subtotal()) return toast(`Cash tendered is short by ${money(subtotal() - tendered)}`, 'error');
    const btn = $('#complete'); btn.disabled = true; btn.textContent = 'Processing…';
    run(async () => {
      const res = await api.closeCustomerOrder(order.id, { paymentMethod: method, cashTendered: tendered });
      toast(`${res.sale.saleId} closed · ${money(res.sale.subtotal)}${res.sale.changeDue ? ' · change ' + money(res.sale.changeDue) : ''}`);
      order = null; $('#cash').value = '';
      inventory = (await api.inventory()).inventory;
    }).finally(() => { btn.disabled = false; btn.textContent = 'Take payment & close order'; });
  });

  $('#add-product').addEventListener('click', () => openProductModal(api, async () => {
    inventory = (await api.inventory()).inventory; drawCatalog();
  }));

  drawCart();
}

function openProductModal(api, onSaved) {
  const bg = document.createElement('div');
  bg.className = 'modal-bg';
  bg.innerHTML = `
    <form class="modal stack" id="product-form">
      <div><h3 style="font-size:19px">Add product</h3><p class="sub" style="margin:4px 0 0">New items appear in the POS instantly.</p></div>
      <div class="field"><label>Name</label><input class="input" name="name" required placeholder="e.g. Sugar (2kg)"/></div>
      <div class="grid g-2" style="gap:12px">
        <div class="field"><label>Barcode</label><input class="input" name="barcode" required pattern="\\d{6,14}" placeholder="6001234567890"/></div>
        <div class="field"><label>Category</label>
          <select class="input" name="category">${['Staples', 'Bakery', 'Dairy', 'Meat & Poultry', 'Beverages', 'Snacks', 'Household'].map((c) => `<option>${c}</option>`).join('')}</select></div>
        <div class="field"><label>Cost (R)</label><input class="input" name="cost" type="number" step="0.01" min="0" required/></div>
        <div class="field"><label>Price (R)</label><input class="input" name="price" type="number" step="0.01" min="0.01" required/></div>
        <div class="field"><label>Quantity</label><input class="input" name="qty" type="number" min="0" required value="10"/></div>
        <div class="field"><label>Low-stock alert</label><input class="input" name="lowStock" type="number" min="0" value="5"/></div>
      </div>
      <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:20px">
        <button type="button" class="btn btn-ghost" id="cancel">Cancel</button>
        <button type="submit" class="btn btn-primary">Save product</button>
      </div>
    </form>`;
  document.body.appendChild(bg);
  const close = () => bg.remove();
  bg.addEventListener('click', (e) => { if (e.target === bg) close(); });
  bg.querySelector('#cancel').addEventListener('click', close);
  bg.querySelector('form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    try {
      const r = await api.addProduct({ ...f, cost: +f.cost, price: +f.price, qty: +f.qty, lowStock: +f.lowStock || 0 });
      toast(`${r.item.name} added`); close(); onSaved();
    } catch (err) { toast(err.message, 'error'); }
  });
  bg.querySelector('input').focus();
}

function openPriceModal(api, product, onSaved) {
  if (!product) return;
  const bg = document.createElement('div'); bg.className = 'modal-bg';
  bg.innerHTML = `<form class="modal stack" id="price-form"><div><h3 style="font-size:19px">Update price</h3><p class="sub" style="margin:4px 0 0">${esc(product.name)}</p></div><div class="field"><label>Price (R)</label><input class="input" name="price" type="number" min="0.01" step="0.01" value="${product.price}" required></div><div style="display:flex;gap:10px;justify-content:flex-end;margin-top:20px"><button type="button" class="btn btn-ghost" id="cancel">Cancel</button><button type="submit" class="btn btn-primary">Save price</button></div></form>`;
  document.body.appendChild(bg); const close = () => bg.remove(); bg.querySelector('#cancel').onclick = close; bg.onclick = e => { if (e.target === bg) close(); };
  bg.querySelector('form').onsubmit = async e => { e.preventDefault(); try { await api.updateProductPrice(product.id, Number(new FormData(e.target).get('price'))); toast(`${product.name} price updated`); close(); await onSaved(); } catch (err) { toast(err.message, 'error'); } };
  bg.querySelector('input').focus();
}
