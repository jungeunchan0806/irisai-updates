package com.theseven.practice;

import static org.junit.Assert.*;

import com.theseven.common.Updates;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;

public class PracticeTest {
  Catalog c;
  List<String> ids;

  static String read(Path p) throws Exception {
    return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
  }

  JSONObject config(String mode, String map, String style) {
    return Catalog.json(
        "{\"mode\":\"" + mode + "\",\"map\":\"" + map + "\",\"style\":\"" + style + "\"}");
  }

  @Before
  public void load() throws Exception {
    c = new Catalog(read(Path.of("src/main/assets/theseven_catalog.json")));
    ids = new ArrayList<>(c.profiles.keySet());
  }

  @Test
  public void fixedIdsAndUnknownLevels() {
    assertEquals(109, ids.size());
    for (String id : List.of("thunder_frogs", "vince", "cosmo", "wendy", "nori")) {
      JSONObject b = c.profiles.get(id);
      assertNotEquals(b.optString("en"), b.optString("ko"));
      assertNull(b.optJSONArray("powers").optJSONObject(0).opt("missing"));
      assertEquals(11, b.optJSONArray("powers").length());
      assertTrue(b.optJSONArray("powers").optJSONObject(10).isNull("super"));
      assertEquals(2, b.optJSONArray("gadget").length());
      assertEquals(2, b.optJSONArray("star").length());
      assertTrue(b.optJSONObject("hyper").isNull("effect"));
    }
  }

  @Test
  public void firstVisible() {
    assertEquals(1, Catalog.pick(ids, 0, ids.get(1), false).optJSONArray("pages").length());
  }

  @Test
  public void distantAndLeftwardPages() {
    JSONObject found = Catalog.pick(ids, 0, "nori", false);
    assertEquals("nori", found.optString("id"));
    assertTrue(found.optJSONArray("pages").length() > 2);
    assertEquals(ids.get(2), Catalog.pick(ids, 6, ids.get(2), false).optString("id"));
  }

  @Test
  public void missingAndLocked() {
    for (JSONObject r :
        List.of(Catalog.pick(ids, 5, "ghost", false), Catalog.pick(ids, 1, "wendy", true))) {
      assertFalse(r.has("id"));
      assertTrue(r.has("error"));
    }
  }

  @Test
  public void cancelAndClaimAreTerminal() {
    JSONObject jobs =
        Catalog.json("{\"a\":{\"status\":\"pending\"},\"b\":{\"status\":\"pending\"}}");
    assertTrue(State.transition(jobs, "a", "cancelled"));
    assertFalse(State.transition(jobs, "a", "done"));
    assertTrue(State.transition(jobs, "b", "done"));
    assertFalse(State.transition(jobs, "b", "done"));
    assertFalse(State.transition(jobs, "b", "cancelled"));
  }

  @Test
  public void choicesNeedPositiveOwnershipAndSlots() {
    JSONObject inv = new JSONObject(), cfg = config("duo", "bush", "support");
    JSONObject r = c.equipment("wendy", cfg, inv, new JSONObject());
    for (String k : Catalog.SLOTS) assertFalse(r.optJSONObject(k).has("id"));
    Catalog.put(inv, "shield", true);
    assertFalse(c.equipment("wendy", cfg, inv, new JSONObject()).optJSONObject("gear1").has("id"));
  }

  @Test
  public void recomputeAutoAndReserveSecondManual() {
    JSONObject inv = Catalog.json("{\"slots\":2,\"shield\":true,\"damage\":true,\"speed\":true}"),
        choices = Catalog.json("{\"gear2\":\"shield\"}");
    JSONObject first = c.equipment("wendy", config("ball", "bush", "aggressive"), inv, choices);
    assertEquals("speed", first.optJSONObject("gear1").optString("id"));
    assertEquals("shield", first.optJSONObject("gear2").optString("id"));
    Catalog.put(inv, "speed", false);
    JSONObject second = c.equipment("wendy", config("duel", "open", "aggressive"), inv, choices);
    assertEquals("damage", second.optJSONObject("gear1").optString("id"));
    Catalog.put(inv, "shield", false);
    JSONObject manual = c.equipment("wendy", new JSONObject(), inv, choices).optJSONObject("gear2");
    assertEquals("shield", manual.optString("selection"));
    assertFalse(manual.has("id"));
  }

  @Test
  public void uniqueActionsInEachPractice() {
    String[][] pairs = {
      {"thunder_frogs", "삼각형 범위 공격", "두 발 소진 · 연습 재장전"},
      {"vince", "나방 방출", "특수 공격 · 표식"},
      {"cosmo", "투사체"},
      {"wendy", "아군 보호막"},
      {"nori", "근거리 베기", "충전 이동"}
    };
    for (String[] pair : pairs) {
      Arena a = new Arena(c.profiles.get(pair[0]), new JSONObject());
      Set<String> actions = new HashSet<>();
      for (int i = 0; i < 70; i++) {
        a.step();
        actions.add(a.action);
      }
      for (int i = 1; i < pair.length; i++) {
        String expected = pair[i];
        assertTrue(pair[0] + actions, actions.stream().anyMatch(v -> v.startsWith(expected)));
      }
      assertTrue(a.points > 0);
    }
  }

  @Test
  public void triangleExcludesTargetsBehindAndToSide() {
    assertTrue(Arena.triangle(2, 0, 70, 3));
    assertFalse(Arena.triangle(2, 0, -40, 0));
    assertFalse(Arena.triangle(2, 0, 40, 50));
  }

  @Test
  public void signedPatchAndTampering() throws Exception {
    Path root = Path.of("../theseven-release");
    JSONObject envelope = new JSONObject(read(root.resolve("theseven-update.json")));
    byte[] bytes = Base64.getDecoder().decode(envelope.getString("payload")),
        sig = Base64.getDecoder().decode(envelope.getString("signature")),
        key = Files.readAllBytes(Path.of("../theseven-common/assets/theseven_patch.der"));
    JSONObject m = Updates.verify(bytes, sig, key);
    assertEquals(2, m.getJSONObject("catalog").getInt("version"));
    bytes[0] ^= 1;
    assertThrows(
        java.security.GeneralSecurityException.class, () -> Updates.verify(bytes, sig, key));
  }
}
