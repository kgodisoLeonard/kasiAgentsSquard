export const rand = value => new Intl.NumberFormat('en-ZA',{style:'currency',currency:'ZAR'}).format(Number(value)||0);
export async function getInsights(api){
 const [inventory,sales,wallet,supplierData] = await Promise.allSettled([api.inventory(),api.sales(),api.xrplStatus(),api.suppliers()]);
 if(inventory.status!=='fulfilled')throw new Error('Inventory API unavailable');
 const products=inventory.value.inventory||[];
 const saleData=sales.status==='fulfilled'?sales.value:null;
 const walletData=wallet.status==='fulfilled'?wallet.value:null;
 const offers=supplierData.status==='fulfilled'?(supplierData.value.suppliers||[]).filter(x=>x.verified):[];
 const low=products.filter(x=>Number(x.qty)<=Number(x.lowStock));
 const value=products.reduce((s,p)=>s+Number(p.qty||0)*Number(p.cost||0),0);
 const health=Math.max(0,Math.min(100,Math.round(100-40*(low.length/Math.max(products.length,1))-Math.min(20,products.filter(p=>Number(p.qty)===0).length*5))));
 const suggested=low.map(p=>({product:p,qty:Math.max(1,Number(p.lowStock)*2-Number(p.qty)),offers:offers.filter(o=>o.product?.toLowerCase()===p.name?.toLowerCase()).sort((a,b)=>a.price-b.price)}));
 return {products,low,value,health,suggested,offers,sales:saleData,wallet:walletData};
}
export function answer(question,data){
 const q=question.toLowerCase(),s=data.sales;
 if(/profit|poelo|inzuzo|phaello|wins|margin/.test(q))return s?`Recorded gross profit: ${rand(s.profit)} across ${s.count} sales. This is not net profit; expenses are not included.`:'Sales history is unavailable; I cannot calculate profit.';
 if(/sale|revenue|today|theki|theng|thengisa|imali|money|cash|income/.test(q))return s?`Recorded revenue: ${rand(s.revenue)} across ${s.count} sales. This is cumulative recorded data, not a verified today-only total.`:'Sales history is unavailable.';
 if(/stock|inventory|restock|order|setoko|sitoko|isitoko|supplier|baabi|hlinzek/.test(q))return data.low.length?`${data.low.length} products need attention: ${data.suggested.map(x=>`${x.product.name} (${x.product.qty} left; suggest ${x.qty} units)`).join('; ')}. These are rule-based recommendations. Review suppliers and approve any order yourself.`:'No products are currently below their reorder thresholds.';
 if(/wallet|treasury|balance|rlusd|risk|fund|afford/.test(q))return data.wallet?`Testnet RLUSD balance: ${data.wallet.rlusdBalance}; transaction cap: ${rand(data.wallet.perTxLimit)}. A full affordability check needs expenses and outstanding obligations, so I cannot authorize a purchase.`:'Wallet information is unavailable.';
 if(/health|score|business|performance|kgwebo|ibhizinisi/.test(q))return `Inventory-based health score: ${data.health}/100. ${data.low.length} low-stock products. Recorded revenue: ${s?rand(s.revenue):'unavailable'}. Score is a simple stock-risk indicator, not a credit rating.`;
 return `I can help with recorded sales, gross profit, stock shortages, suppliers and testnet wallet status. Try asking “Which products need restocking?” or “How much profit is recorded?”`;
}
