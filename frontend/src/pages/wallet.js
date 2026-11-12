import { toast } from '../main.js';

export async function renderWallet(root, api) {
  const status = await api.xrplStatus();

  root.innerHTML = `
    <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
      <div class="card">
        <h3 class="font-bold text-white mb-3">XRPL Testnet Wallet</h3>
        <div class="space-y-3">
          <div>
            <div class="text-xs text-slate-400">RLUSD Balance</div>
            <div class="text-2xl font-bold font-mono text-white">1,250.00 <span class="text-sm text-emerald-400">RLUSD</span></div>
          </div>
          <div>
            <div class="text-xs text-slate-400">XRP Balance</div>
            <div class="text-lg font-bold font-mono text-slate-300">48.50 <span class="text-sm text-slate-400">XRP</span></div>
          </div>
          <div class="text-xs text-slate-500 pt-2 border-t border-slate-800">
            Network: ${status.network}<br/>
            RLUSD Issuer: <span class="font-mono text-slate-400">${status.rlusdIssuer.substring(0, 16)}...</span>
          </div>
        </div>
      </div>

      <div class="card md:col-span-2">
        <h3 class="font-bold text-white mb-3">Send XRPL Testnet Payment</h3>
        <div class="space-y-3">
          <div>
            <label class="text-xs text-slate-400">Destination Address</label>
            <input id="dest" value="${status.rlusdIssuer}" class="w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded-xl text-xs font-mono text-white"/>
          </div>
          <div>
            <label class="text-xs text-slate-400">Amount (RLUSD)</label>
            <input id="amount" type="number" value="250" class="w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded-xl text-white"/>
          </div>
          <button id="pay" class="btn-primary w-full">Send XRPL Payment</button>
          <div id="log" class="bg-slate-950 p-4 rounded-xl border border-slate-800 font-mono text-xs text-slate-300 space-y-1 max-h-40 overflow-y-auto">
            <div>[SYSTEM] Connected to ${status.wsUrl}</div>
            <div>[SYSTEM] Ready for submission...</div>
          </div>
        </div>
      </div>
    </div>
  `;

  root.querySelector('#pay').addEventListener('click', async () => {
    const amount = parseFloat(root.querySelector('#amount').value);
    const destination = root.querySelector('#dest').value.trim();
    const log = root.querySelector('#log');
    try {
      log.innerHTML += `<div>[SUBMIT] Sending ${amount} RLUSD...</div>`;
      const res = await api.pay({ amount, destination });
      log.innerHTML += `<div class="text-emerald-400">[SUCCESS] tesSUCCESS</div>`;
      log.innerHTML += `<div class="text-slate-400 text-[10px]">Hash: ${res.hash.substring(0, 32)}...</div>`;
      log.scrollTop = log.scrollHeight;
      toast('Payment sent: ' + res.hash.substring(0, 12) + '...');
    } catch (err) {
      log.innerHTML += `<div class="text-rose-400">[BLOCKED] ${err.message}</div>`;
      toast(err.message, 'error');
    }
  });
}
