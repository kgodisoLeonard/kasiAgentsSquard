package com.spaza.repository;

import org.springframework.stereotype.Component;
import java.util.*;

/** Human-owned guardrails. The agent reads these rules; it cannot change them. */
@Component
public class AgentPolicy {
  private int warningThreshold = 10;
  private int autoOrderThreshold = 5;
  private boolean autoOrderEnabled = false;
  private boolean xrplTestnetOnly = true;
  private double agentSpentToday = 0.0;
  private static final double PER_TRANSACTION_CAP = 1000.0, DAILY_CAP = 5000.0, HUMAN_APPROVAL_ABOVE = 2000.0;
  private static final List<Map<String,Object>> ALLOW_LIST = List.of(
    item("Maize Meal (10kg)","Staples",105),item("Rice (10kg)","Staples",150),item("Sugar (2.5kg)","Staples",60),item("Cooking Oil (2L)","Staples",75),item("Flour (10kg)","Staples",120),item("Salt (1kg)","Staples",15),
    item("Chicken Pieces (2kg)","Proteins",110),item("Minced Meat (1kg)","Proteins",90),item("Eggs (18 pack)","Proteins",65),item("Tinned Fish (400g)","Proteins",35),item("Soya Mince (1kg)","Proteins",50),
    item("White Bread (700g)","Bakery & Dairy",18),item("Full Cream Milk (2L)","Bakery & Dairy",32),item("Amasi (1L)","Bakery & Dairy",25),item("Margarine (500g)","Bakery & Dairy",40),item("Cheese (400g)","Bakery & Dairy",60),
    item("Coca-Cola (2L)","Beverages",26),item("Oros (2L)","Beverages",55),item("Black Tea (100 bags)","Beverages",45),item("Instant Coffee (200g)","Beverages",60),
    item("Washing Powder (2kg)","Household & Hygiene",65),item("Dishwashing Liquid (750ml)","Household & Hygiene",30),item("Toilet Paper (9 pack)","Household & Hygiene",55),item("Bath Soap (175g)","Household & Hygiene",15),item("Candles (6 pack)","Household & Hygiene",25));
  public synchronized Map<String,Object> get() { return Map.of("warningThreshold",warningThreshold,"autoOrderThreshold",autoOrderThreshold,"autoOrderEnabled",autoOrderEnabled,"xrplTestnetOnly",xrplTestnetOnly,"approvalRequiredAbove",HUMAN_APPROVAL_ABOVE,"purchasingMandate",mandate()); }
  public synchronized Map<String,Object> update(Map<String,Object> b) {
    int warning=((Number)b.getOrDefault("warningThreshold",warningThreshold)).intValue(); int auto=((Number)b.getOrDefault("autoOrderThreshold",autoOrderThreshold)).intValue();
    if(warning < 1 || auto < 0 || auto > warning) throw new IllegalArgumentException("Auto-order threshold must be between 0 and the warning threshold");
    warningThreshold=warning; autoOrderThreshold=auto; autoOrderEnabled=Boolean.TRUE.equals(b.get("autoOrderEnabled")); return get();
  }
  public synchronized int warningThreshold(){return warningThreshold;} public synchronized int autoOrderThreshold(){return autoOrderThreshold;} public synchronized boolean autoOrderEnabled(){return autoOrderEnabled;}
  public synchronized Map<String,Object> mandate(){return Map.of("mandate_id","MANDATE-SPAZA-001","status","ACTIVE","agent_permissions",Map.of("can_buy",true,"can_modify_rules",false,"requires_human_approval_above",HUMAN_APPROVAL_ABOVE),"spending_limits",Map.of("currency","RLUSD","per_transaction_cap",PER_TRANSACTION_CAP,"daily_cap",DAILY_CAP,"spent_today",agentSpentToday),"allow_list",ALLOW_LIST,"blocked_actions",List.of("Cannot purchase items not in allow_list","Cannot pay above max_unit_price","Cannot exceed per_transaction_cap or daily_cap","Cannot transfer funds to unverified suppliers"));}
  public synchronized Map<String,Object> approvePurchase(String item,double unitPrice,int quantity,boolean verifiedSupplier){
    Map<String,Object> allowed=ALLOW_LIST.stream().filter(x->x.get("item").equals(item)).findFirst().orElse(null); double total=unitPrice*quantity;
    if(allowed==null)return Map.of("allowed",false,"reason","Item is not in the allow_list");
    if(!verifiedSupplier)return Map.of("allowed",false,"reason","Supplier is not verified");
    if(unitPrice>((Number)allowed.get("max_unit_price")).doubleValue())return Map.of("allowed",false,"reason","Unit price exceeds mandate maximum");
    if(total>PER_TRANSACTION_CAP)return Map.of("allowed",false,"reason","Transaction exceeds the RLUSD per-transaction cap");
    if(agentSpentToday+total>DAILY_CAP)return Map.of("allowed",false,"reason","Transaction exceeds the RLUSD daily cap");
    agentSpentToday+=total; return Map.of("allowed",true,"total",total,"currency","RLUSD","spent_today",agentSpentToday,"network","XRPL Testnet","status","PAYMENT_DRAFT_APPROVED");
  }
  private static Map<String,Object> item(String name,String category,double max){return Map.of("item",name,"category",category,"max_unit_price",max);}
}
