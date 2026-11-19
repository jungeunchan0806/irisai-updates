package com.theseven.practice;

import com.theseven.practice.Geometry.Box;
import com.theseven.practice.Geometry.P;
import java.util.*;

/** Android port of IrisAI 0.0.9 aggressive_universal.iris (see upstream/). */
public final class FarmPolicy {
  public static final class Brawler {
    public String name = "", superType = "damage";
    public double safeRange, attackRange, superRange, holdSeconds;
    public boolean throughWalls, superThroughWalls;
  }

  public static final class Frame {
    public P player;
    public List<P> enemies = Collections.emptyList();
    public List<Box> walls = Collections.emptyList();
    public boolean superReady, gadgetReady, hyperReady;
  }

  public static final class Decision {
    public double dx, dy;
    public boolean attack, superAttack, gadget, hyper;
    public String phase = "적 찾는 중";
  }

  private boolean retreat;
  private P previous = new P(0, -75);

  public void reset() {
    retreat = false;
    previous = new P(0, -75);
  }

  private static boolean clear(Frame f, P target, boolean throughWalls) {
    return throughWalls || Geometry.clear(f.player, target, f.walls, 0);
  }

  private static P movement(Frame f, P[] choices) {
    for (P move : choices) {
      double length = Math.hypot(move.x, move.y);
      if (length < 1) continue;
      P target = new P(f.player.x + move.x / length * 38, f.player.y + move.y / length * 38);
      if (Geometry.clear(f.player, target, f.walls, 17)) return move;
    }
    // A detector box can overlap a stationary player's inflated footprint.
    // Let A* project that start onto nearby free space instead of returning
    // zero forever before its overlap-recovery branch can run.
    for (Box wall : f.walls)
      if (wall.contains(f.player, 17)) {
        for (P move : choices) if (Math.hypot(move.x, move.y) >= 1) return move;
      }
    return new P(0, 0);
  }

  public Decision decide(Frame f, Brawler b) {
    Decision d = new Decision();
    if (f.player == null) {
      d.phase = "캐릭터 찾는 중";
      return d;
    }
    P enemy = null;
    double distance = Double.POSITIVE_INFINITY;
    for (P p : f.enemies) {
      double n = f.player.distance(p);
      if (n < distance && clear(f, p, b.throughWalls)) {
        enemy = p;
        distance = n;
      }
    }
    P move;
    if (enemy == null) {
      retreat = false;
      move =
          movement(f, new P[] {previous, new P(0, -75), new P(-75, 0), new P(75, 0), new P(0, 75)});
    } else {
      if (b.safeRange <= 0) retreat = false;
      else if (distance < b.safeRange * .85) retreat = true;
      else if (distance > b.safeRange * 1.15) retreat = false;
      double dx = (enemy.x - f.player.x) * (retreat ? -1 : 1),
          dy = (enemy.y - f.player.y) * (retreat ? -1 : 1);
      move =
          movement(
              f,
              new P[] {
                new P(dx, dy),
                new P(0, dy),
                new P(dx, 0),
                new P(0, -75),
                new P(-75, 0),
                new P(0, 75),
                new P(75, 0)
              });
      d.phase = retreat ? "거리 유지" : "적에게 접근";
      double abilityRange = b.superRange;
      if (b.superType.equals("spawnable") || b.superType.equals("other"))
        abilityRange = Math.max(abilityRange, Math.max(b.safeRange, b.attackRange * .75));
      else if ((b.name.equals("stu") || b.name.equals("surge")) && b.superType.equals("charge"))
        abilityRange += b.attackRange;
      d.superAttack =
          f.superReady && distance <= abilityRange && clear(f, enemy, b.superThroughWalls);
      d.hyper = d.superAttack && f.hyperReady;
      d.attack = distance <= b.attackRange && clear(f, enemy, b.throughWalls);
      d.gadget =
          d.attack
              && f.gadgetReady
              && distance <= (b.safeRange > 0 ? b.safeRange : b.attackRange * .75);
    }
    double length = Math.hypot(move.x, move.y);
    if (length >= 1) {
      P target =
          new P(
              Math.max(130, Math.min(830, f.player.x + move.x / length * 120)),
              Math.max(65, Math.min(465, f.player.y + move.y / length * 120)));
      P way = Geometry.waypoint(f.player, target, f.walls);
      double distanceToWay = f.player.distance(way);
      if (distanceToWay > 1) {
        d.dx = (way.x - f.player.x) / distanceToWay * 75;
        d.dy = (way.y - f.player.y) / distanceToWay * 75;
      }
    }
    if (Math.hypot(d.dx, d.dy) > 1) previous = new P(d.dx, d.dy);
    return d;
  }
}
