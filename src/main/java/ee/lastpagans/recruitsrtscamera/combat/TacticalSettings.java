package ee.lastpagans.recruitsrtscamera.combat;

/** Persistent player choices; temporary orders and ability timers live separately. */
public record TacticalSettings(CombatMode combat, boolean skirmish, ShieldMode shield, boolean holdFire) {
    public enum CombatMode { NATIVE, RANGED, MELEE }
    public enum ShieldMode { NATIVE, FRONT, UP, AUTO }
    public static final TacticalSettings DEFAULT = new TacticalSettings(CombatMode.NATIVE, false, ShieldMode.NATIVE, false);
    public TacticalSettings withCombat(CombatMode value) { return new TacticalSettings(value, skirmish, shield, holdFire); }
    public TacticalSettings withSkirmish(boolean value) { return new TacticalSettings(combat, value, shield, holdFire); }
    public TacticalSettings withShield(ShieldMode value) { return new TacticalSettings(combat, skirmish, value, holdFire); }
    public TacticalSettings withHoldFire(boolean value) { return new TacticalSettings(combat, skirmish, shield, value); }
}
