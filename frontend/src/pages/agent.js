import { toast } from '../main.js';

export async function renderAgent(root, api) {
  const data = await api.shortages();
  const go = data.groupOrders[0];

  root.innerHTML = `
    <div class="card">
      <h2 class="text-xl font-bold mb-2">AI Agent Shortage Detection &amp; Group Buying</h2>
      <p class="text-sm text-slate-400 mb-4">${data.recommendation}</p>

      <div class="bg-gradient-to-r from-emerald-950/50 to-slate-900 border border-emerald-500/30 rounded-2xl p-5 mb-6 flex flex-col md:flex-row justify-between gap-4">
        <div>
          <span class="px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 text-xs font-mono border border-emerald-500/30">Shortage Detected</span>
          <h3 class="text-lg font-bold text-white mt-2">${go.product} Bulk Restock (${go.targetQty} kg)</h3>
          <p class="text-sm text-slate-300">${go.community} - Wholesale R${go.wholesalePrice}/kg (save R${(go.unitPrice - go.wholesalePrice).toFixed(2)}/kg) + R${go.deliveryFee} delivery</p>
        </div>
        <button id="approve" class="btn-primary">Approve My Share</button>
      </div>

      <h3 class="font-bold text-white mb-3">Fair Cost-Split (10 kg share)</h3>
      <table class="w-full text-left text-sm">
        <thead class="bg-slate-950 text-slate-400 text-xs uppercase font-mono border-b border-slate-800">
          <tr><th class="p-3">Item</th><th class="p-3">Amount</th></tr>
        </thead>
        <tbody class="divide-y divide-slate-800">
          <tr><td class="p-3 text-white">Goods (10 kg @ R90)</td><td class="p-3 font-mono text-slate-200">R900.00</td></tr>
          <tr><td class="p-3 text-white">Delivery share (1/5)</td><td class="p-3 font-mono text-slate-200">R50.00</td></tr>
          <tr><td class="p-3 text-white font-bold">Due in 30 days</td><td class="p-3 font-mono text-emerald-400 font-bold">R950.00</td></tr>
        </tbody>
      </table>
    </div>
  `;

  root.querySelector('#approve').addEventListener('click', async () => {
    try {
      const res = await api.approveGroupBuy({ quantity: 10 });
      toast(res.message);
      root.querySelector('#approve').disabled = true;
      root.querySelector('#approve').textContent = 'Approved';
    } catch (err) { toast(err.message, 'error'); }
  });
}
