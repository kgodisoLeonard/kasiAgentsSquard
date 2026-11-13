import './styles.css';
import { api } from './api.js';
import { icons, loader, errorBox } from './ui.js';
import { renderDashboard } from './pages/dashboard.js';
import { renderAgent } from './pages/agent.js';
import { renderWallet } from './pages/wallet.js';
import { renderMandates } from './pages/mandates.js';
import { renderDisputes } from './pages/disputes.js';
import { renderPlatform } from './pages/platform.js';
import { renderRules } from './pages/rules.js';
import { renderLogin } from './pages/login.js';
import { renderSupplier } from './pages/supplier.js';
import { renderMarketplace } from './pages/marketplace.js';

let user = null;
try { user = JSON.parse(localStorage.getItem('spaza_user') || 'null'); }
catch { localStorage.removeItem('spaza_user'); }
const ownerRoutes = {
  dashboard: { label: 'Dashboard', title: 'Good day, Thandi 👋', sub: "Here's how your spaza is doing today.", render: renderDashboard },
  marketplace: { label: 'Marketplace', title: 'Supplier Marketplace', sub: 'Choose verified products and place stock orders.', render: renderMarketplace },
  agent: { label: 'Group Buys', title: 'AI Group Buying', sub: 'Your agent spots shortages and pools orders with nearby shops.', render: renderAgent },
  wallet: { label: 'Wallet', title: 'RLUSD Wallet', sub: 'Send stablecoin payments on the XRPL Testnet.', render: renderWallet },
  mandates: { label: 'Mandates', title: 'Spending Mandates', sub: 'Hard limits your AI agent can never exceed.', render: renderMandates },
  disputes: { label: 'Disputes', title: 'Disputes & Evidence', sub: 'Tamper-evident, SHA-256 fingerprinted evidence packs.', render: renderDisputes },
  platform: { label: 'Trust Centre', title: 'Trust Centre', sub: 'Verification, marketplace, credit and security controls.', render: renderPlatform },
  rules: { label: 'Human Rules', title: 'Human Rules', sub: 'Owner controls for agent stock and ordering decisions.', render: renderRules },
};
const routes = user?.role === 'SUPPLIER'
  ? { supplier: { label: 'Supplier Orders', title: 'Supplier Orders', sub: 'Agent-created orders from verified spaza owners.', render: renderSupplier } }
  : ownerRoutes;

if (!user) {
  renderLogin(document.getElementById('app'), api);
} else {

const navButtons = (cls = '', only) => Object.entries(routes).filter(([id]) => !only || only.includes(id)).map(([id, r]) => `
  <button class="nav-btn ${cls}" data-tab="${id}" id="nav-${id}${cls ? '-m' : ''}">${icons[id] || ''}<span>${r.label}</span></button>`).join('');

document.getElementById('app').innerHTML = `
  <header class="mobile-header">
    <button class="burger" id="menu-open" aria-label="Open menu" aria-expanded="false"><span></span><span></span><span></span></button>
    <div class="brand"><div class="logo" style="width:36px;height:36px;font-size:17px">S</div><h1>Spaza to the World</h1></div>
    <span class="status-dot" id="api-dot-m"></span>
  </header>
  <div class="overlay" id="overlay"></div>
  <div class="shell">
    <aside class="sidebar" id="sidebar">
      <div class="brand">
        <div class="logo">S</div>
        <div><h1>Spaza to the World</h1><p>Kasi Agents Squad</p></div>
        <button class="close-btn" id="menu-close" aria-label="Close menu">✕</button>
      </div>
      <nav class="nav"><div class="nav-label">Menu</div>${navButtons()}</nav>
      <div class="sidebar-foot">
        <div><span class="status-dot" id="api-dot"></span><span id="api-status">Checking API…</span></div>
        <div style="margin-top:6px;color:var(--dim)">XRPL Testnet · RLUSD</div>
        <button class="btn btn-ghost btn-block" id="logout" style="margin-top:14px">Log out</button>
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div><h2 id="page-title"></h2><p id="page-sub"></p></div>
        <span class="chip green" id="clock"></span>
      </header>
      <section id="content"></section>
    </main>
  </div>
  <nav class="mobile-nav">${navButtons('m', ['dashboard', 'marketplace', 'agent', 'wallet'])}
    <button class="nav-btn m" id="more-btn"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg><span>More</span></button>
  </nav>
`;

const sidebar = document.getElementById('sidebar');
const overlay = document.getElementById('overlay');
function setMenu(open) {
  sidebar.classList.toggle('open', open);
  overlay.classList.toggle('show', open);
  document.body.classList.toggle('no-scroll', open);
  document.getElementById('menu-open').setAttribute('aria-expanded', String(open));
}
document.getElementById('menu-open').addEventListener('click', () => setMenu(true));
document.getElementById('more-btn').addEventListener('click', () => setMenu(true));
document.getElementById('menu-close').addEventListener('click', () => setMenu(false));
overlay.addEventListener('click', () => setMenu(false));
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setMenu(false); });
document.getElementById('logout').addEventListener('click', () => { localStorage.removeItem('spaza_user'); window.location.assign(window.location.pathname + window.location.search); });

const content = document.getElementById('content');
let renderToken = 0;

async function showTab(id) {
  if (!routes[id]) {
    if (routes.dashboard) id = 'dashboard';
    else return window.location.reload();
  }
  const r = routes[id];
  document.querySelectorAll('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.tab === id));
  document.getElementById('page-title').textContent = r.title;
  document.getElementById('page-sub').textContent = r.sub;
  document.title = `${r.label} · Spaza to the World`;
  const token = ++renderToken;
  content.innerHTML = loader();
  try {
    await r.render(content, api, () => token === renderToken);
  } catch (err) {
    if (token === renderToken) content.innerHTML = errorBox(err.message);
  }
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-tab]');
  if (b) { location.hash = b.dataset.tab; setMenu(false); }
});
window.addEventListener('hashchange', () => showTab(location.hash.slice(1)));

async function checkHealth() {
  const dot = document.getElementById('api-dot');
  const label = document.getElementById('api-status');
  try {
    await api.health();
    dot.className = 'status-dot on'; label.textContent = 'API online';
    document.getElementById('api-dot-m').className = 'status-dot on';
  } catch {
    dot.className = 'status-dot off'; label.textContent = 'API offline';
    document.getElementById('api-dot-m').className = 'status-dot off';
  }
}

function tickClock() {
  document.getElementById('clock').textContent = '● ' + new Date().toLocaleString('en-ZA', { weekday: 'short', hour: '2-digit', minute: '2-digit' });
}

checkHealth(); setInterval(checkHealth, 15000);
tickClock(); setInterval(tickClock, 30000);
showTab(location.hash.slice(1) || (user.role === 'SUPPLIER' ? 'supplier' : 'dashboard'));
}
