package com.theseven.practice;

import com.theseven.practice.Geometry.P;
import java.util.*;
import org.json.*;

/** Coordinates, turns and points below belong to the local drill, not live-game stats. */
final class Arena {
  final FarmPolicy policy = new FarmPolicy();
  final FarmPolicy.Brawler profile = new FarmPolicy.Brawler();
  final String mechanic;
  final boolean twoRoundReload;
  double x = 310, y = 260, enemyX = 560, enemyY = 230, allyX = 360, allyY = 300, mothX, mothY;
  int turn, points, hits, ammo = 2, shield, mark, charge, projectiles;
  boolean moth, guarded;
  String action = "연습 준비";

  Arena(JSONObject b, JSONObject config) {
    mechanic = b.optString("mechanic");
    twoRoundReload = "after_two_rounds".equals(b.optJSONObject("reload").optString("type"));
    profile.name = b.optString("id");
    JSONObject previous = b.optJSONObject("legacy");
    profile.attackRange = previous == null ? 275 : previous.optDouble("attack_range", 500) * .5;
    profile.safeRange = previous == null ? 130 : previous.optDouble("safe_range", 250) * .5;
    profile.superRange = previous == null ? 310 : previous.optDouble("super_range", 500) * .5;
    if (previous != null) {
      profile.superType = previous.optString("super_type", "damage");
      profile.throughWalls = previous.optBoolean("ignore_walls_for_attacks");
      profile.superThroughWalls = previous.optBoolean("ignore_walls_for_supers");
    }
    if ("aggressive".equals(config.optString("style"))) profile.safeRange *= .75;
    if ("defensive".equals(config.optString("style"))) profile.safeRange *= 1.15;
    if ("duo".equals(config.optString("mode"))) allyY = 250;
    if ("open".equals(config.optString("map"))) enemyX += 75;
  }

  static boolean triangle(double dx, double dy, double forward, double side) {
    double length = Math.hypot(dx, dy);
    if (length == 0) return false;
    double along = (forward * dx + side * dy) / length,
        cross = Math.abs((side * dx - forward * dy) / length);
    return along >= 0 && along <= 285 && cross <= along * .45;
  }

  void step() {
    turn++;
    guarded = false;
    enemyX = 550 + Math.cos(turn * .065) * 55;
    enemyY = 250 + Math.sin(turn * .13) * 65;
    FarmPolicy.Frame f = new FarmPolicy.Frame();
    f.player = new P(x, y);
    f.enemies = List.of(new P(enemyX, enemyY));
    f.superReady = turn % 8 == 0;
    FarmPolicy.Decision d = policy.decide(f, profile);
    x += d.dx * .12;
    y += d.dy * .12;
    double dx = enemyX - x, dy = enemyY - y, dist = Math.hypot(dx, dy);
    action = d.phase;
    if (moth) {
      double flight = Math.hypot(enemyX - mothX, enemyY - mothY);
      if (flight < 85) {
        moth = false;
        hit(mark > 0 ? 2 : 1);
      } else {
        mothX += (enemyX - mothX) * 82 / flight;
        mothY += (enemyY - mothY) * 82 / flight;
      }
    }
    if (mark > 0) mark--;
    if (shield > 0) shield--;
    if (mechanic.equals("shield_ally") && turn % 5 == 0) {
      if (shield > 0) {
        guarded = true;
        points++;
      }
      shield = 0;
    }
    if (twoRoundReload && ammo == 0) {
      ammo = 2;
      action = "두 발 소진 · 연습 재장전";
      return;
    }
    if (mechanic.equals("slash_charge") && dist > 150) {
      if (++charge >= 2) {
        x += dx * .42;
        y += dy * .42;
        charge = 0;
        action = "충전 이동";
      } else action = "이동 충전";
      return;
    }
    if (mechanic.equals("shield_ally") && shield == 0 && !guarded) {
      shield = 6;
      action = "아군 보호막";
      return;
    }
    if (!d.attack && !d.superAttack) return;
    switch (mechanic) {
      case "triangle":
        ammo--;
        projectiles++;
        action = "삼각형 범위 공격";
        if (triangle(dx, dy, dx, dy)) hit(1);
        if (triangle(dx, dy, dx - 15, dy + 40)) hit(1);
        break;
      case "moth_mark":
        if (d.superAttack) {
          mark = 7;
          action = "특수 공격 · 표식";
        } else if (!moth) {
          moth = true;
          mothX = x;
          mothY = y;
          projectiles++;
          action = "나방 방출";
        }
        break;
      case "distance":
        int band = dist < 165 ? 1 : 2;
        hit(band);
        action = "투사체 · " + (band == 1 ? "근거리" : "원거리") + " 연습 판정";
        break;
      case "slash_charge":
        charge = 0;
        hit(1);
        action = "근거리 베기";
        break;
      case "shield_ally":
        hit(1);
        action = guarded ? "아군 공격 차단" : "일반 공격";
        break;
      default:
        hit(d.superAttack ? 2 : 1);
        action = d.superAttack ? "특수 공격" : "일반 공격";
    }
  }

  void hit(int value) {
    points += value;
    hits++;
  }

  JSONObject frame() {
    JSONObject o = new JSONObject();
    Object[][] fields = {
      {"x", x},
      {"y", y},
      {"enemyX", enemyX},
      {"enemyY", enemyY},
      {"allyX", allyX},
      {"allyY", allyY},
      {"action", action},
      {"points", points},
      {"hits", hits},
      {"shield", shield},
      {"mark", mark},
      {"moth", moth},
      {"mothX", mothX},
      {"mothY", mothY},
      {"turn", turn}
    };
    for (Object[] field : fields) Catalog.put(o, (String) field[0], field[1]);
    return o;
  }
}
