package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Human Rules take precedence over the agent's restock rules: when the owner switches automatic ordering off, or sets
 * a lower threshold, the agent does not order, whatever the restock rule says. Rules are per owner.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:humanrules;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=", "spaza.restock.sweep-enabled=false"})
class HumanRulesTest {
  @LocalServerPort int port;

  /** Takes bread down to exactly `left` through the till and returns the agent's restock result. */
  static JsonNode sellBreadDownTo(Api owner, int left) {
    JsonNode bread = FeatureApiTest.product(owner, FeatureApiTest.BREAD);
    int sell = bread.get("qty").asInt() - left;
    assertTrue(sell > 0, "test needs more bread than " + left);
    JsonNode added = FeatureApiTest.sellAtTill(owner, bread.get("id").asText(), sell);
    return added.get("restock") == null ? null : added.get("restock").get("result");
  }

  @Test void humanRulesOverrideRestockRules() {
    Api sipho = new Api(port).login("Sipho's Corner Shop", "spaza123");      // Sipho's restock rule: bread below 6 -> order 20
    Api thandi = new Api(port).login("Thandi's Spaza", "spaza123");

    // 1. Owner switches automatic ordering off: bread falls below the restock rule, the agent must not order.
    Api.Res saved = sipho.put("/human/rules", Map.of("warningThreshold", 10, "autoOrderThreshold", 5, "autoOrderEnabled", false));
    assertEquals(200, saved.status(), saved.body().toString());
    JsonNode held = sellBreadDownTo(sipho, 4);
    assertNotNull(held, "the till should report what the agent did");
    assertEquals("HELD_BY_HUMAN_RULES", held.get("status").asText(), held.toString());
    assertTrue(FeatureApiTest.product(sipho, FeatureApiTest.BREAD).get("onOrder").isNull(), "no order may be placed");

    // Rules are per owner: Thandi's are untouched.
    assertTrue(thandi.get("/human/rules").body().get("rules").get("autoOrderEnabled").asBoolean());

    // 2. Ordering on, but only below 3: at 4 the human threshold wins over the restock rule (below 6).
    sipho.put("/human/rules", Map.of("warningThreshold", 10, "autoOrderThreshold", 3, "autoOrderEnabled", true));
    Api.Res check = sipho.post("/pos/restock-check", Map.of());
    assertEquals(200, check.status());
    assertTrue(FeatureApiTest.product(sipho, FeatureApiTest.BREAD).get("onOrder").isNull(), "stock 4 is not below the human threshold 3");

    // 3. Below both thresholds: now the agent orders, exactly per the restock rule.
    JsonNode ordered = sellBreadDownTo(sipho, 2);
    assertEquals("ORDERED", ordered.get("status").asText(), ordered.toString());
    assertEquals(20, ordered.get("quantity").asInt());
  }

  @Test void agentRulesPageReportsHumanPrecedence() {
    Api thandi = new Api(port).login("Thandi's Spaza", "spaza123");
    JsonNode rules = thandi.get("/human/rules").body().get("rules");
    assertTrue(rules.has("autoOrderEnabled") && rules.has("autoOrderThreshold"));
    assertEquals(403, new Api(port).login("Ubuntu Foods Co-op", "supplier123").put("/human/rules", Map.of("autoOrderEnabled", true)).status(),
        "only owners set Human Rules");
  }
}
