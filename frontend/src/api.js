const BASE = '/api';

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(BASE + path, {
      ...options,
      headers: options.body ? { 'Content-Type': 'application/json' } : undefined,
    });
  } catch {
    throw new Error('Cannot reach the backend — is it running on port 8080?');
  }
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  if (!res.ok) {
    const message = data && (data.error || data.reason || data.message);
    throw new Error(message || (res.status === 422 ? 'Action blocked by the purchasing mandate.' : `Request failed (${res.status})`));
  }
  return data;
}

const post = (path, body) => request(path, { method: 'POST', body: JSON.stringify(body) });

export const api = {
  login: (body) => post('/auth/login', body),
  health: () => request('/health'),
  inventory: () => request('/pos/inventory'),
  inventoryByBarcode: (b) => request(`/pos/inventory/barcode/${encodeURIComponent(b)}`),
  addProduct: (body) => post('/pos/inventory', body),
  sale: (body) => post('/pos/sale', body),
  sales: () => request('/pos/sales'),
  shortages: () => request('/agent/shortages'),
  approveGroupBuy: (body) => post('/agent/group-buy/approve', body),
  xrplStatus: () => request('/xrpl/status'),
  pay: (body) => post('/xrpl/pay', body),
  mandates: () => request('/mandates'),
  createMandate: (body) => post('/mandates', body),
  evidencePack: (ref) => request(`/disputes/${encodeURIComponent(ref)}/evidence-pack`),
  platformOverview: () => request('/platform/overview'),
  suppliers: () => request('/platform/suppliers'),
  notifications: () => request('/platform/notifications'),
  stepUp: (action) => post('/platform/step-up', { action }),
  orders: () => request('/platform/orders'),
  createOrder: (body) => post('/platform/orders', body),
  supplierAction: (id, action) => post(`/platform/orders/${encodeURIComponent(id)}/supplier-action`, { action }),
  confirmDelivery: (id) => post(`/platform/orders/${encodeURIComponent(id)}/owner-confirmation`),
  agentRules: () => request('/human/rules'),
  saveAgentRules: (body) => request('/human/rules', {method:'PUT',body:JSON.stringify(body)}),
  agents: () => request('/agents'),
  setAgentEnabled: (id, enabled) => post(`/agents/${encodeURIComponent(id)}/enabled`, { enabled }),
};
