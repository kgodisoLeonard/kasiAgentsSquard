const BASE = '/api';
async function json(res) {
  if (!res.ok) throw new Error((await res.json()).error || 'Request failed');
  return res.json();
}
export const api = {
  health: () => fetch(`${BASE}/health`).then(json),
  inventory: () => fetch(`${BASE}/pos/inventory`).then(json),
  inventoryByBarcode: (b) => fetch(`${BASE}/pos/inventory/barcode/${b}`).then(json),
  addProduct: (body) => fetch(`${BASE}/pos/inventory`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}).then(json),
  sale: (body) => fetch(`${BASE}/pos/sale`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}).then(json),
  shortages: () => fetch(`${BASE}/agent/shortages`).then(json),
  approveGroupBuy: (body) => fetch(`${BASE}/agent/group-buy/approve`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}).then(json),
  xrplStatus: () => fetch(`${BASE}/xrpl/status`).then(json),
  pay: (body) => fetch(`${BASE}/xrpl/pay`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}).then(json),
  mandates: () => fetch(`${BASE}/mandates`).then(json),
  createMandate: (body) => fetch(`${BASE}/mandates`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}).then(json),
  evidencePack: (ref) => fetch(`${BASE}/disputes/${ref}/evidence-pack`).then(json),
};
