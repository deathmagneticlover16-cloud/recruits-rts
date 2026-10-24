package ee.lastpagans.recruitsrtscamera.combat;

/** Dependency-free cavalry plan. Terrain navigation consumes these waypoints in the adapter. */
public final class CavalryManeuver {
    private static final double STAGING_DISTANCE = 22.0D;
    private static final double PASS_DISTANCE = 5.5D;
    private static final double TURN_RADIUS = 3.0D;
    private static final int TURN_STEPS = 12;
    private static final double WAYPOINT_REACHED_SQ = 3.25D * 3.25D;
    public record Point(double x, double z) {
        public Point add(Point p) { return new Point(x + p.x, z + p.z); }
        public Point minus(Point p) { return new Point(x - p.x, z - p.z); }
        public Point scale(double n) { return new Point(x * n, z * n); }
        public double lengthSquared() { return x * x + z * z; }
        public double dot(Point p) { return x * p.x + z * p.z; }
    }
    public enum Phase { APPROACH, CHARGE, PASS, TURN, REFORM }
    private Phase phase;
    private Point impact, direction, approach, exit, turnCenter, turnRadial;
    private final double preferredTurnSign;
    private double activeTurnSign;
    private int turnStep;
    private int turnRetries;

    public CavalryManeuver(Point start, Point target, double turnSign) {
        this.preferredTurnSign = turnSign < 0 ? -1 : 1;
        this.activeTurnSign = this.preferredTurnSign;
        prepare(start, target);
    }
    public Phase phase() { return phase; }
    public Point committedImpact() { return impact; }
    public Point committedExit() { return exit; }
    private void prepare(Point position, Point target) {
        Point delta = target.minus(position);
        double distance = Math.sqrt(delta.lengthSquared());
        direction = distance > 0.1 ? delta.scale(1 / distance) : new Point(0, 1);
        impact = target;
        approach = impact.minus(direction.scale(STAGING_DISTANCE));
        exit = impact.add(direction.scale(PASS_DISTANCE));
        activeTurnSign = preferredTurnSign;
        turnRetries = 0;
        phase = distance > 26 ? Phase.APPROACH : Phase.CHARGE;
    }
    private void commitCharge(Point position, Point target) {
        Point delta = target.minus(position);
        double distance = Math.sqrt(delta.lengthSquared());
        if (distance > 0.1D) direction = delta.scale(1.0D / distance);
        impact = target;
        exit = impact.add(direction.scale(PASS_DISTANCE));
        phase = Phase.CHARGE;
    }
    public Point tick(Point position, Point liveTarget) {
        switch (phase) {
            case APPROACH:
                Point waypoint = approach;
                if (position.minus(waypoint).lengthSquared() < 9) {
                    commitCharge(position, liveTarget == null ? impact : liveTarget);
                    return exit;
                }
                return waypoint;
            case CHARGE:
                if (position.minus(impact).dot(direction) > 2) phase = Phase.PASS;
                return exit;
            case PASS:
                if (position.minus(exit).lengthSquared() < 9) {
                    beginTurn(position);
                    return turnWaypoint(turnStep);
                }
                return exit;
            case TURN:
                Point turnPoint = turnWaypoint(turnStep);
                if (position.minus(turnPoint).lengthSquared() < WAYPOINT_REACHED_SQ) {
                    if (++turnStep > TURN_STEPS) {
                        phase = Phase.REFORM;
                        // Carry speed out of the hairpin until the next pass is planned.
                        return turnPoint.add(direction.scale(-1.5D));
                    }
                    else turnPoint = turnWaypoint(turnStep);
                }
                return turnPoint;
            case REFORM:
                if (liveTarget == null) return position;
                prepare(position, liveTarget);
                return phase == Phase.APPROACH ? approach : exit;
            default: throw new IllegalStateException("Unknown cavalry phase");
        }
    }
    /** Returns true only after both broad turn directions prove obstructed. */
    public boolean blocked(Point position) {
        if (phase == Phase.TURN && turnRetries++ == 0) {
            activeTurnSign = -activeTurnSign;
            beginTurn(position);
            turnRetries = 1;
            return false;
        }
        if (phase == Phase.TURN) { phase = Phase.REFORM; return true; }
        beginTurn(position);
        return false;
    }
    private void beginTurn(Point position) {
        phase = Phase.TURN;
        turnStep = 1;
        Point normal = new Point(-direction.z * activeTurnSign, direction.x * activeTurnSign);
        turnCenter = position.add(normal.scale(TURN_RADIUS));
        turnRadial = normal.scale(-TURN_RADIUS);
    }
    private Point turnWaypoint(int step) {
        double angle = activeTurnSign * Math.min(step, TURN_STEPS) * Math.PI / TURN_STEPS;
        double sin = Math.sin(angle), cos = Math.cos(angle);
        Point rotated = new Point(turnRadial.x * cos - turnRadial.z * sin,
                turnRadial.x * sin + turnRadial.z * cos);
        return turnCenter.add(rotated);
    }
}
