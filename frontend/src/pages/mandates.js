export async function renderMandates(root, api) {
  const data = await api.mandates();

  root.innerHTML = `
    <div class="card">
      <h2 class="text-xl font-bold mb-2">t54-Style Spending Mandates</h2>
      <p class="text-sm text-slate-400 mb-4">Hard limits enforced outside the AI agent.</p>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        ${data.mandates.map(m => `
          <div class="bg-slate-950 border border-slate-800 rounded-xl p-5">
            <div class="flex justify-between items-start mb-2">
              <span class="text-xs bg-emerald-500/20 text-emerald-400 px-2 py-0.5 rounded font-mono border border-emerald-500/30">${m.id}</span>
              <span class="text-xs text-slate-400">Expires ${m.expires}</span>
            </div>
            <h4 class="font-bold text-white">${m.title}</h4>
            <div class="mt-3 space-y-1 text-xs font-mono">
              <div class="flex justify-between"><span class="text-slate-500">Per-Tx Cap:</span><span class="text-emerald-400">R${m.perTxCap}</span></div>
              <div class="flex justify-between"><span class="text-slate-500">Daily Cap:</span><span class="text-emerald-400">R${m.dailyCap}</span></div>
              <div class="flex justify-between"><span class="text-slate-500">Spent Today:</span><span class="text-white">R${m.spentToday}</span></div>
            </div>
          </div>
        `).join('')}
      </div>
    </div>
  `;
}
