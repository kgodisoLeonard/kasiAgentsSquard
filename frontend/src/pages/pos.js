import { toast } from '../main.js';

export async function renderPOS(root, api) {
  const data = await api.inventory();
  const inventory = data.inventory;
  const cart = [];
  let paymentMethod = 'cash';

  root.innerHTML = `
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
      <div class="card lg:col-span-2">
        <h2 class="text-xl font-bold mb-2">Phone-Scan Point of Sale</h2>
        <p class="text-sm text-slate-400 mb-4">Scan or tap a product to add it to the cart.</p>
        <input id="search" placeholder="Search product or barcode..." class="w-full px-4 py-3 bg-slate-950 border border-slate-700 rounded-xl mb-4 text-white"/>
        <div id="catalog" class="grid grid-cols-2 sm:grid-cols-3 gap-4"></div>
      </div>
      <div class="card">
        <h3 class="font-bold text-lg mb-3 text-white">Current Sale</h3>
        <div id="cart" class="space-y-2 mb-4 max-h-64 overflow-y-auto"></div>
        <div class="border-t border-slate-800 pt-3 text-sm space-y-1">
          <div class="flex justify-between"><span class="text-slate-400">Subtotal</span><span id="subtotal" class="font-mono text-white">R0.00</span></div>
          <div class="flex justify-between"><span class="text-slate-400">Profit</span><span id="profit" class="font-mono text-emerald-400">R0.00</span></div>
          <div class="flex justify-between font-bold pt-2 border-t border-slate-800"><span>Total</span><span id="total" class="font-mono text-emerald-400">R0.00</span></div>
        </div>
        <div class="grid grid-cols-3 gap-2 mt-3" id="pay-methods">
          <button data-m="cash" class="pay-btn border border-emerald-500 bg-emerald-600/20 text-emerald-400 rounded-xl py-2 text-xs font-semibold">Cash</button>
          <button data-m="card" class="pay-btn border border-slate-700 bg-slate-800 text-slate-300 rounded-xl py-2 text-xs font-semibold">Card</button>
          <button data-m="rlusd" class="pay-btn border border-slate-700 bg-slate-800 text-slate-300 rounded-xl py-2 text-xs font-semibold">RLUSD</button>
        </div>
        <input id="cash" type="number" placeholder="Cash tendered (R)" class="w-full mt-3 px-3 py-2 bg-slate-950 border border-slate-700 rounded-xl text-sm text-white"/>
        <div class="flex justify-between text-xs text-slate-400 mt-1"><span>Change due</span><span id="change" class="font-mono text-emerald-400">R0.00</span></div>
        <button id="complete" class="btn-primary w-full mt-4">Complete Sale</button>
      </div>
    </div>
  `;

  const catalog = root.querySelector('#catalog');
  const drawCatalog = (filter = '') => {
    catalog.innerHTML = inventory
      .filter(p => p.name.toLowerCase().includes(filter.toLowerCase()) || p.barcode.includes(filter))
      .map(p => `
        <button data-id="${p.id}" class="bg-slate-950 border ${p.qty <= p.lowStock ? 'border-amber-500/50' : 'border-slate-800'} rounded-xl p-4 text-left hover:border-emerald-500/50 transition">
          <div class="text-xs text-slate-400 font-mono">${p.category}</div>
          <div class="font-bold text-sm mt-1 text-white">${p.name}</div>
          <div class="text-xs text-slate-500 font-mono">${p.barcode}</div>
          <div class="flex justify-between mt-2 pt-2 border-t border-slate-900">
            <span class="font-mono text-emerald-400 font-bold">R${p.price.toFixed(2)}</span>
            <span class="text-xs text-slate-400">Stock: ${p.qty}</span>
          </div>
        </button>
      `).join('') || '<div class="col-span-full text-center text-slate-500 py-6 text-sm">No products found</div>';
  };
  drawCatalog();
  root.querySelector('#search').addEventListener('input', (e) => drawCatalog(e.target.value));
  catalog.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-id]');
    if (!btn) return;
    const item = inventory.find(p => p.id === btn.dataset.id);
    const existing = cart.find(c => c.id === item.id);
    if (existing) existing.qty++;
    else cart.push({ id: item.id, name: item.name, price: item.price, cost: item.cost, qty: 1 });
    drawCart();
  });

  const drawCart = () => {
    const cartEl = root.querySelector('#cart');
    if (cart.length === 0) {
      cartEl.innerHTML = '<div class="text-center py-6 text-slate-500 text-sm">Cart is empty</div>';
    } else {
      cartEl.innerHTML = cart.map((c, i) => `
        <div class="flex justify-between items-center bg-slate-950 p-2 rounded-lg border border-slate-800">
          <div>
            <div class="text-sm font-bold text-white">${c.name}</div>
            <div class="text-xs text-slate-400">R${c.price.toFixed(2)} x ${c.qty}</div>
          </div>
          <div class="flex items-center gap-2">
            <span class="font-mono text-emerald-400 text-sm font-bold">R${(c.price * c.qty).toFixed(2)}</span>
            <button data-del="${i}" class="text-rose-400 text-xs">x</button>
          </div>
        </div>
      `).join('');
      cartEl.querySelectorAll('button[data-del]').forEach(b => {
        b.addEventListener('click', () => { cart.splice(+b.dataset.del, 1); drawCart(); });
      });
    }
    const subtotal = cart.reduce((s, c) => s + c.price * c.qty, 0);
    const profit = cart.reduce((s, c) => s + (c.price - c.cost) * c.qty, 0);
    root.querySelector('#subtotal').textContent = 'R' + subtotal.toFixed(2);
    root.querySelector('#profit').textContent = 'R' + profit.toFixed(2);
    root.querySelector('#total').textContent = 'R' + subtotal.toFixed(2);
  };
  drawCart();

  root.querySelector('#pay-methods').addEventListener('click', (e) => {
    const b = e.target.closest('.pay-btn');
    if (!b) return;
    paymentMethod = b.dataset.m;
    root.querySelectorAll('.pay-btn').forEach(x => {
      x.className = 'pay-btn border rounded-xl py-2 text-xs font-semibold ' +
        (x.dataset.m === paymentMethod
          ? 'border-emerald-500 bg-emerald-600/20 text-emerald-400'
          : 'border-slate-700 bg-slate-800 text-slate-300');
    });
  });

  root.querySelector('#cash').addEventListener('input', () => {
    const sub = cart.reduce((s, c) => s + c.price * c.qty, 0);
    const tendered = parseFloat(root.querySelector('#cash').value) || 0;
    const change = Math.max(0, tendered - sub);
    root.querySelector('#change').textContent = 'R' + change.toFixed(2);
  });

  root.querySelector('#complete').addEventListener('click', async () => {
    if (cart.length === 0) return toast('Cart is empty', 'error');
    try {
      const res = await api.sale({
        items: cart.map(c => ({ productId: c.id, quantity: c.qty })),
        paymentMethod,
        cashTendered: parseFloat(root.querySelector('#cash').value) || 0,
      });
      toast('Sale ' + res.sale.saleId + ' completed! Profit R' + res.sale.profit.toFixed(2));
      cart.length = 0;
      const fresh = await api.inventory();
      inventory.length = 0;
      inventory.push(...fresh.inventory);
      drawCatalog();
      drawCart();
    } catch (err) { toast(err.message, 'error'); }
  });
}
