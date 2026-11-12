import { api } from './api.js';
import { renderPOS } from './pages/pos.js';
import { renderAgent } from './pages/agent.js';
import { renderWallet } from './pages/wallet.js';
import { renderMandates } from './pages/mandates.js';
import { renderDisputes } from './pages/disputes.js';

const app = document.getElementById('app');
app.innerHTML = `
  <header class="bg-slate-900 border-b border-slate-800 sticky top-0 z-50">
    <div class="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 bg-gradient-to-tr from-emerald-600 to-teal-400 rounded-xl flex items-center justify-center text-xl font-bold text-white">S</div>
        <div>
          <h1 class="text-lg font-bold">Spaza to the World</h1>
          <p class="text-xs text-slate-400">Agentic Finance - XRPL Testnet - Java Backend</p>
        </div>
      </div>
      <div class="text-xs bg-slate-800 px-3 py-1.5 rounded-lg border border-slate-700 text-emerald-400 font-mono">Live</div>
    </div>
    <nav class="max-w-7xl mx-auto px-4 flex gap-2 overflow-x-auto">
      <button data-tab="pos" class="tab-btn py-3 px-4 text-sm font-medium border-b-2 border-transparent text-slate-400 whitespace-nowrap">POS</button>
      <button data-tab="agent" class="tab-btn py-3 px-4 text-sm font-medium border-b-2 border-transparent text-slate-400 whitespace-nowrap">Group Buys</button>
      <button data-tab="wallet" class="tab-btn py-3 px-4 text-sm font-medium border-b-2 border-transparent text-slate-400 whitespace-nowrap">Wallet &amp; XRPL</button>
      <button data-tab="mandates" class="tab-btn py-3 px-4 text-sm font-medium border-b-2 border-transparent text-slate-400 whitespace-nowrap">Mandates</button>
      <button data-tab="disputes" class="tab-btn py-3 px-4 text-sm font-medium border-b-2 border-transparent text-slate-400 whitespace-nowrap">Disputes</button>
    </nav>
  </header>
  <main id="content" class="max-w-7xl mx-auto px-4 py-6"></main>
  <div id="toast" class="fixed bottom-6 right-6 z-50"></div>
`;

const content = document.getElementById('content');
const tabs = { pos: renderPOS, agent: renderAgent, wallet: renderWallet, mandates: renderMandates, disputes: renderDisputes };

function showTab(id) {
  document.querySelectorAll('.tab-btn').forEach(b => {
    b.classList.toggle('border-emerald-500', b.dataset.tab === id);
    b.classList.toggle('text-emerald-400', b.dataset.tab === id);
    b.classList.toggle('border-transparent', b.dataset.tab !== id);
    b.classList.toggle('text-slate-400', b.dataset.tab !== id);
  });
  tabs[id](content, api);
}
document.querySelectorAll('.tab-btn').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));

export function toast(msg, type = 'success') {
  const el = document.createElement('div');
  el.className = `mb-3 px-4 py-3 rounded-xl border text-sm ${
    type === 'success' ? 'bg-emerald-950/90 border-emerald-500/40 text-emerald-200' : 'bg-rose-950/90 border-rose-500/40 text-rose-200'}`;
  el.textContent = msg;
  document.getElementById('toast').appendChild(el);
  setTimeout(() => el.remove(), 3500);
}

showTab('pos');
