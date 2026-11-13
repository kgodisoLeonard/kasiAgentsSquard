package com.spaza.finance;

import com.spaza.audit.AuditLog;
import com.spaza.persistence.StateStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.*;
import java.util.function.Predicate;

/**
 * SIMULATED XRPL Testnet ledger. No transaction is signed or submitted to a real network:
 * balances, escrows and transactions are kept in memory, written to the state store on every change and reloaded at
 * startup; transaction hashes are SHA-256 digests of the simulated transaction.
 *
 * It mirrors XRPL's escrow model (EscrowCreate / EscrowFinish / EscrowCancel) so the money flow is real
 * in shape: a member's share moves from the member's wallet into an escrow whose destination is fixed
 * to the verified supplier's payout address. Finish pays the supplier directly; cancel returns the funds
 * to the member who paid. There is no agent wallet and no intermediary account.
 */
public class SimulatedXrplLedger {
  public static final BigDecimal STARTING_BALANCE = new BigDecimal("5000.00");

  public record Tx(String hash, String type, String from, String to, BigDecimal amount, String memo, String timestamp) {
    public Map<String,Object> view() {
      Map<String,Object> v = new LinkedHashMap<>();
      v.put("hash", hash); v.put("type", type); v.put("from", from); v.put("to", to);
      v.put("amount", amount); v.put("memo", memo); v.put("timestamp", timestamp); v.put("simulated", true);
      return v;
    }
  }
  public static final class Escrow {
    final String id, owner, destination; final BigDecimal amount; String status = "HELD";
    Escrow(String id, String owner, String destination, BigDecimal amount) { this.id = id; this.owner = owner; this.destination = destination; this.amount = amount; }
    public String id() { return id; } public String owner() { return owner; } public String destination() { return destination; }
    public BigDecimal amount() { return amount; } public String status() { return status; }
  }

  private final Clock clock;
  private final Predicate<String> verifiedDestination;
  private final Map<String,BigDecimal> balances = new HashMap<>();
  private final Map<String,Escrow> escrows = new LinkedHashMap<>();
  private final List<Tx> txs = new ArrayList<>();
  private final StateStore store;

  /** Stored forms. */
  record EscrowState(String id, String owner, String destination, BigDecimal amount, String status) {}
  record TxState(int seq, Tx tx) {}

  public SimulatedXrplLedger(Clock clock, Predicate<String> verifiedDestination) { this(clock, verifiedDestination, StateStore.memory()); }

  public SimulatedXrplLedger(Clock clock, Predicate<String> verifiedDestination, StateStore store) {
    this.clock = clock; this.verifiedDestination = verifiedDestination; this.store = store;
    balances.putAll(store.loadMap("ledger-balance", BigDecimal.class));
    for (EscrowState e : store.loadAll("ledger-escrow", EscrowState.class)) {
      Escrow x = new Escrow(e.id(), e.owner(), e.destination(), e.amount()); x.status = e.status(); escrows.put(x.id, x);
    }
    store.loadAll("ledger-tx", TxState.class).stream().sorted(Comparator.comparingInt(TxState::seq)).forEach(t -> txs.add(t.tx()));
  }

  private void setBalance(String account, BigDecimal amount) { balances.put(account, amount); store.save("ledger-balance", account, amount); }
  private void saveEscrow(Escrow e) { store.save("ledger-escrow", e.id, new EscrowState(e.id, e.owner, e.destination, e.amount, e.status)); }

  public static String walletOf(String ownerId) { return "member-wallet:" + ownerId; }

  public synchronized BigDecimal balance(String account) {
    return balances.computeIfAbsent(account, a -> a.startsWith("member-wallet:") ? STARTING_BALANCE : BigDecimal.ZERO);
  }

  public synchronized Tx createEscrow(String ownerWallet, String supplierAddress, BigDecimal amount, String memo) {
    amount = money(amount);
    requireVerified(supplierAddress);
    if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
    if (balance(ownerWallet).compareTo(amount) < 0) throw new IllegalStateException("Insufficient simulated RLUSD balance");
    setBalance(ownerWallet, balance(ownerWallet).subtract(amount));
    Tx tx = tx("EscrowCreate", ownerWallet, supplierAddress, amount, memo);
    Escrow created = new Escrow(tx.hash(), ownerWallet, supplierAddress, amount);
    escrows.put(tx.hash(), created); saveEscrow(created);
    return tx;
  }

  /** Releases a held escrow to its fixed destination (the verified supplier). */
  public synchronized Tx finishEscrow(String escrowId, String memo) {
    Escrow e = held(escrowId);
    requireVerified(e.destination);
    e.status = "FINISHED"; saveEscrow(e);
    setBalance(e.destination, balance(e.destination).add(e.amount));
    return tx("EscrowFinish", e.owner, e.destination, e.amount, memo);
  }

  /** Returns a held escrow to the member who funded it. */
  public synchronized Tx cancelEscrow(String escrowId, String memo) {
    Escrow e = held(escrowId);
    e.status = "CANCELLED"; saveEscrow(e);
    setBalance(e.owner, balance(e.owner).add(e.amount));
    return tx("EscrowCancel", e.destination, e.owner, e.amount, memo);
  }

  /** Direct payment from a member wallet to a verified supplier (used by the wallet page, after the mandate check). */
  public synchronized Tx pay(String ownerWallet, String supplierAddress, BigDecimal amount, String memo) {
    amount = money(amount);
    requireVerified(supplierAddress);
    if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
    if (balance(ownerWallet).compareTo(amount) < 0) throw new IllegalStateException("Insufficient simulated RLUSD balance");
    setBalance(ownerWallet, balance(ownerWallet).subtract(amount));
    setBalance(supplierAddress, balance(supplierAddress).add(amount));
    return tx("Payment", ownerWallet, supplierAddress, amount, memo);
  }

  public synchronized Optional<Escrow> escrow(String id) { return Optional.ofNullable(escrows.get(id)); }

  public synchronized List<Tx> transactionsFor(String account) {
    return txs.stream().filter(t -> account.equals(t.from()) || account.equals(t.to())).toList();
  }

  private Escrow held(String id) {
    Escrow e = escrows.get(id);
    if (e == null) throw new NoSuchElementException("Escrow not found");
    if (!"HELD".equals(e.status)) throw new IllegalStateException("Escrow already " + e.status.toLowerCase(Locale.ROOT));
    return e;
  }

  private void requireVerified(String address) {
    if (!verifiedDestination.test(address)) throw new IllegalStateException("Ledger refused: destination is not a verified supplier payout address");
  }

  private Tx tx(String type, String from, String to, BigDecimal amount, String memo) {
    String ts = clock.instant().toString();
    String hash = AuditLog.sha256(String.join("|", type, from, to, amount.toPlainString(), memo == null ? "" : memo, ts, Integer.toString(txs.size()))).toUpperCase(Locale.ROOT);
    Tx tx = new Tx(hash, type, from, to, amount, memo, ts);
    txs.add(tx);
    store.save("ledger-tx", hash, new TxState(txs.size(), tx));
    return tx;
  }

  static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
