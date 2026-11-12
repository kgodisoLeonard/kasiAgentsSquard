import { esc, money, toast, icons, emojiFor } from '../ui.js';

export async function renderPOS(root, api, alive) {
  const data = await api.inventory();
  if (!alive()) return;
  let inventory = data.inventory;
  const cart = new Map(); // id -> qty
  let method = 'cash';
  let filter = '';

  root.innerHTML = `
    <div class="grid g-3">
      <div class="card span-2">
        <div class="card-head">
          <div><h3>Products</h3><p class="sub">Tap a product or type a barcode and press Enter</p></div>
          <button class="btn btn-ghost btn-sm" id="add-product">+ New product</button>
        </div>
        <div class="search">${icons.search}<input id="search" class="input" placeholder="Search name or scan barcode…" autocomplete="off"/></div>
        <div id="catalog" class="products"></div>
      </div>

      <div class="card" style="align-self:start;position:sticky;top:20px">
        <div class="card-head"><div><h3>Current sale</h3><p class="sub" id="cart-count">0 items</p></div>
          <button class="btn btn-ghost btn-sm" id="clear">Clear</button></div>
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
        <button id="complete" class="btn btn-primary btn-block" style="margin-top:16px;padding:14px">Complete sale</button>
      </div>
    </div>`;

  const $ = (s) => root.querySelector(s);
  const byId = (id) => inventory.find((p) => p.id === id);
  const totals = () => {
    let sub = 0, profit = 0, count = 0;
    cart.forEach((q, id) => { const p = byId(id); if (p) { sub += p.price * q; profit += (p.price - p.cost) * q; count += q; } });
    return { sub, profit, count };
  };

  function drawCatalog() {
    const f = filter.toLowerCase();
    const list = inventory.filter((p) => p.name.toLowerCase().includes(f) || p.barcode.includes(f) || (p.category || '').toLowerCase().includes(f));
    $('#catalog').innerHTML = list.map((p) => {
      const left = p.qty - (cart.get(p.id) || 0);
      return `
      <div class="product ${p.qty <= p.lowStock ? 'low' : ''} ${left <= 0 ? 'out' : ''}" data-id="${esc(p.id)}" title="Click to update price">
        <div class="emoji">${emojiFor(p.category)}</div>
        <div class="name">${esc(p.name)}</div>
        <div class="meta">${esc(p.barcode)}</div>
        <div class="foot"><span class="price">${money(p.price)}</span><span class="stock">${left <= 0 ? 'Sold out' : left + ' left'}</span></div>
        <div style="display:flex;gap:6px;margin-top:8px"><button type="button" class="btn btn-ghost btn-sm" data-edit-price="${esc(p.id)}">Update price</button><button type="button" class="btn btn-primary btn-sm" data-add="${esc(p.id)}">Add</button></div>
      </div>`;
    }).join('') || `<div class="empty" style="grid-column:1/-1"><div class="big">🔍</div>No products match “${esc(filter)}”</div>`;
  }

  function drawCart() {
    const { sub, profit, count } = totals();
    $('#cart').innerHTML = cart.size ? [...cart].map(([id, q]) => {
      const p = byId(id);
      return `
      <div class="cart-item">
        <div style="font-size:22px">${emojiFor(p.category)}</div>
        <div class="info"><b>${esc(p.name)}</b><span>${money(p.price)} each</span></div>
        <div class="qty"><button data-dec="${esc(id)}" aria-label="Decrease">−</button><span>${q}</span><button data-inc="${esc(id)}" aria-label="Increase">+</button></div>
      </div>`;
    }).join('') : `<div class="empty"><div class="big">🛒</div>Cart is empty</div>`;
    $('#cart-count').textContent = `${count} item${count === 1 ? '' : 's'}`;
    $('#subtotal').textContent = money(sub);
    $('#profit').textContent = money(profit);
    $('#total').textContent = money(sub);
    updateChange();
    drawCatalog();
  }

  function updateChange() {
    const t = parseFloat($('#cash').value) || 0;
    $('#change').textContent = money(Math.max(0, t - totals().sub));
  }

  function add(id) {
    const p = byId(id); if (!p) return;
    const q = cart.get(id) || 0;
    if (q >= p.qty) return toast(`Only ${p.qty} ${p.name} in stock`, 'error');
    cart.set(id, q + 1); drawCart();
  }

  $('#catalog').addEventListener('click', (e) => { const edit = e.target.closest('[data-edit-price]'); if (edit) return openPriceModal(api, byId(edit.dataset.editPrice), async () => { inventory = (await api.inventory()).inventory; drawCart(); }); const addButton = e.target.closest('[data-add]'); if (addButton) return add(addButton.dataset.add); const card = e.target.closest('[data-id]'); if (card) openPriceModal(api, byId(card.dataset.id), async () => { inventory = (await api.inventory()).inventory; drawCart(); }); });
  $('#cart').addEventListener('click', (e) => {
    const inc = e.target.closest('[data-inc]'); const dec = e.target.closest('[data-dec]');
    if (inc) add(inc.dataset.inc);
    if (dec) { const id = dec.dataset.dec; const q = cart.get(id) - 1; q > 0 ? cart.set(id, q) : cart.delete(id); drawCart(); }
  });
  $('#search').addEventListener('input', (e) => { filter = e.target.value.trim(); drawCatalog(); });
  $('#search').addEventListener('keydown', async (e) => {
    if (e.key !== 'Enter' || !/^\d{6,14}$/.test(filter)) return;
    try { const r = await api.inventoryByBarcode(filter); add(r.item.id); e.target.value = ''; filter = ''; drawCatalog(); toast(`Scanned ${r.item.name}`); }
    catch (err) { toast(err.message, 'error'); }
  });
  $('#clear').addEventListener('click', () => { cart.clear(); drawCart(); });
  $('#cash').addEventListener('input', updateChange);
  $('#methods').addEventListener('click', (e) => {
    const b = e.target.closest('[data-m]'); if (!b) return;
    method = b.dataset.m;
    root.querySelectorAll('#methods button').forEach((x) => x.classList.toggle('active', x === b));
    $('#cash-box').style.display = method === 'cash' ? '' : 'none';
  });

  $('#complete').addEventListener('click', async () => {
    const { sub } = totals();
    if (!cart.size) return toast('Cart is empty', 'error');
    const tendered = parseFloat($('#cash').value) || 0;
    if (method === 'cash' && tendered < sub) return toast(`Cash tendered is short by ${money(sub - tendered)}`, 'error');
    const btn = $('#complete'); btn.disabled = true; btn.textContent = 'Processing…';
    try {
      const res = await api.sale({ items: [...cart].map(([productId, quantity]) => ({ productId, quantity })), paymentMethod: method, cashTendered: tendered });
      toast(`${res.sale.saleId} done · ${money(res.sale.subtotal)}${res.sale.changeDue ? ' · change ' + money(res.sale.changeDue) : ''}`);
      cart.clear(); $('#cash').value = '';
      inventory = (await api.inventory()).inventory;
      drawCart();
    } catch (err) { toast(err.message, 'error'); }
    finally { btn.disabled = false; btn.textContent = 'Complete sale'; }
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
