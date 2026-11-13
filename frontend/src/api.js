import { askCode } from './ui.js';

const BASE = import.meta.env.VITE_API_BASE || '/api';
const SESSION_KEY = 'spaza_session';

/** Tokens live in sessionStorage (cleared when the tab closes). The access token expires after 15 minutes. */
export const session = {
  get() { try { return JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null'); } catch { return null; } },
  set(value) { sessionStorage.setItem(SESSION_KEY, JSON.stringify(value)); },
  clear() { sessionStorage.removeItem(SESSION_KEY); localStorage.removeItem('spaza_user'); },
  user() { return this.get()?.user || null; },
};

export function deviceId() {
  let id = localStorage.getItem('spaza_device');
  if (!id) { id = crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2) + Date.now(); localStorage.setItem('spaza_device', id); }
  return id;
}

let refreshing = null;
async function refreshTokens() {
  const s = session.get();
  if (!s?.refreshToken) return false;
  refreshing ||= fetch(BASE + '/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: s.refreshToken }) })
    .then(async (res) => {
      if (!res.ok) return false;
      const data = await res.json();
      session.set({ ...s, accessToken: data.accessToken, refreshToken: data.refreshToken, user: data.user || s.user });
      return true;
    })
    .catch(() => false)
    .finally(() => { setTimeout(() => { refreshing = null; }, 0); });
  return refreshing;
}

function signedOut() {
  session.clear();
  window.location.assign(window.location.pathname);
}

async function request(path, options = {}, retry = true) {
  const s = session.get();
  const headers = {};
  if (options.body) headers['Content-Type'] = 'application/json';
  if (s?.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  if (options.stepUp) headers['X-Step-Up'] = options.stepUp;
  let res;
  try {
    res = await fetch(BASE + path, { method: options.method || 'GET', body: options.body, headers });
  } catch {
    throw new Error('Cannot reach the backend — is it running on port 8080?');
  }
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  // Only an expired/invalid session triggers refresh or sign-out. A wrong one-time code is a normal error.
  if (res.status === 401 && data?.code === 'SESSION_EXPIRED' && s?.accessToken) {
    if (retry && await refreshTokens()) return request(path, options, false);
    signedOut();
    throw new Error('Your session expired. Please sign in again.');
  }
  if (!res.ok) {
    const err = new Error((data && (data.error || data.reason || data.message)) || `Request failed (${res.status})`);
    err.status = res.status; err.code = data?.code; err.action = data?.action;
    throw err;
  }
  return data;
}

const post = (path, body, opts = {}) => request(path, { method: 'POST', body: JSON.stringify(body ?? {}), ...opts });
const put = (path, body, opts = {}) => request(path, { method: 'PUT', body: JSON.stringify(body ?? {}), ...opts });

/**
 * Runs an action; if the server answers STEP_UP_REQUIRED, asks for a one-time code and retries once with the grant.
 * Returns null if the user cancels.
 */
export async function withStepUp(run) {
  try { return await run(undefined); }
  catch (err) {
    if (err.code !== 'STEP_UP_REQUIRED') throw err;
    const challenge = await api.stepUp(err.action);
    const code = await askCode({
      title: 'Confirm it’s you',
      message: `Extra verification is needed to ${err.action}.`,
      demoCode: challenge.demoCode,
    });
    if (!code) return null;
    const grant = await api.stepUpVerify(challenge.challengeId, code);
    return run(grant.stepUpToken);
  }
}

export const api = {
  login: (body) => post('/auth/login', body),
  verifyMfa: (challengeId, code) => post('/auth/mfa/verify', { challengeId, code, deviceId: deviceId() }),
  register: (body) => post('/auth/register', body),
  me: () => request('/auth/me'),
  logout: () => post('/auth/logout'),
  logoutAll: () => post('/auth/logout-all'),
  stepUp: (action) => post('/auth/step-up', { action }),
  stepUpVerify: (challengeId, code) => post('/auth/step-up/verify', { challengeId, code }),
  health: () => request('/health'),

  inventory: () => request('/pos/inventory'),
  inventoryByBarcode: (b) => request(`/pos/inventory/barcode/${encodeURIComponent(b)}`),
  addProduct: (body) => post('/pos/inventory', body),
  updateProductPrice: (id, price) => put(`/pos/inventory/${encodeURIComponent(id)}/price`, { price }),
  sale: (body) => post('/pos/sale', body),
  openCustomerOrder: () => request('/pos/orders/open'),
  newCustomerOrder: () => post('/pos/orders'),
  addToOrder: (orderId, productId, quantity = 1) => post(`/pos/orders/${encodeURIComponent(orderId)}/items`, { productId, quantity }),
  removeFromOrder: (orderId, productId, quantity = 1) => post(`/pos/orders/${encodeURIComponent(orderId)}/items/${encodeURIComponent(productId)}/remove`, { quantity }),
  cancelCustomerOrder: (orderId) => post(`/pos/orders/${encodeURIComponent(orderId)}/cancel`),
  closeCustomerOrder: (orderId, body) => post(`/pos/orders/${encodeURIComponent(orderId)}/close`, body),
  restockCheck: () => post('/pos/restock-check'),
  sales: () => request('/pos/sales'),

  shortages: () => request('/agent/shortages'),
  agentRules: () => request('/agent/rules'),
  agentAction: (action, params) => post('/agent/actions', { action, params }),
  agentNotifications: () => request('/agent/notifications'),
  llmStatus: () => request('/agent/llm'),
  llmRun: (message) => post('/agent/llm/run', { message }),
  humanRules: () => request('/human/rules'),
  saveAgentRules: (body) => put('/human/rules', body),
  agents: () => request('/agents'),
  setAgentEnabled: (id, enabled) => post(`/agents/${encodeURIComponent(id)}/enabled`, { enabled }),

  xrplStatus: () => request('/xrpl/status'),
  pay: (body, stepUp) => post('/xrpl/pay', body, { stepUp }),

  mandate: () => request('/mandates'),
  saveMandate: (body, stepUp) => put('/mandates', body, { stepUp }),
  saveAutonomy: (body, stepUp) => put('/mandates/autonomy', body, { stepUp }),

  groupOrders: () => request('/group-orders'),
  groupMembers: () => request('/group-orders/members'),
  createGroupOrder: (body) => post('/group-orders', body),
  approveShare: (id, stepUp) => post(`/group-orders/${encodeURIComponent(id)}/approve`, {}, { stepUp }),
  declineShare: (id) => post(`/group-orders/${encodeURIComponent(id)}/decline`),
  confirmDelivery: (id) => post(`/group-orders/${encodeURIComponent(id)}/confirm-delivery`),
  reportNonDelivery: (id, reason) => post(`/group-orders/${encodeURIComponent(id)}/report-non-delivery`, { reason }),
  supplierAction: (id, action) => post(`/group-orders/${encodeURIComponent(id)}/supplier-action`, { action }),
  resolveDispute: (id, outcome, reasons) => post(`/group-orders/${encodeURIComponent(id)}/resolve`, { outcome, reasons }),

  disputes: () => request('/disputes'),
  evidencePack: (orderId) => request(`/disputes/${encodeURIComponent(orderId)}/evidence-pack`),
  audit: () => request('/audit'),
  verifyAudit: () => request('/audit/verify'),

  platformOverview: () => request('/platform/overview'),
  suppliers: () => request('/platform/suppliers'),
  directory: () => request('/platform/directory'),
  rateSupplier: (supplierId, body) => post(`/platform/suppliers/${encodeURIComponent(supplierId)}/ratings`, body),
  notifications: () => request('/platform/notifications'),
  ownerSuppliers: (ownerId) => request(`/platform/owners/${encodeURIComponent(ownerId)}/suppliers`),
  addOwnerSupplier: (ownerId, supplierId) => post(`/platform/owners/${encodeURIComponent(ownerId)}/suppliers`, { supplierId }),
  removeOwnerSupplier: (ownerId, supplierId) => request(`/platform/owners/${encodeURIComponent(ownerId)}/suppliers/${encodeURIComponent(supplierId)}`, { method: 'DELETE' }),
};
