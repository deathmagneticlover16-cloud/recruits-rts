package ee.lastpagans.recruitsrtscamera.combat;

/** Dependency-free cavalry plan. Terrain navigation consumes these waypoints in the adapter. */
public final class CavalryManeuver {
    public record Point(double x, double z) {
        public Point add(Point p) { return new Point(x + p.x, z + p.z); }
        public Point minus(Point p) { return new Point(x - p.x, z - p.z); }
        public Point scale(double n) { return new Point(x * n, z * n); }
        public double lengthSquared() { return x * x + z * z; }
        public double dot(Point p) { return x * p.x + z * p.z; }
    }
    public enum Phase { APPROACH, CHARGE, PASS, TURN, REFORM }
    private Phase phase;
    private Point impact, direction, approach, exit, turnOrigin;
    private final double turnSign;
    private int turnStep;

    public CavalryManeuver(Point start, Point target, double turnSign) {
        this.turnSign = turnSign < 0 ? -1 : 1;
        prepare(start, target);
    }
    public Phase phase() { return phase; }
    public Point committedImpact() { return impact; }
    public Point committedExit() { return exit; }
    private void prepare(Point position, Point target) {
        impact = target;
        Point delta = target.minus(position);
        double distance = Math.sqrt(delta.lengthSquared());
        direction = distance > 0.1 ? delta.scale(1 / distance) : new Point(0, 1);
        approach = impact.minus(direction.scale(22));
        exit = impact.add(direction.scale(18));
        phase = distance > 26 ? Phase.APPROACH : Phase.CHARGE;
    }
    public Point tick(Point position, Point liveTarget) {
        switch (phase) {
            case APPROACH:
                Point waypoint = approach;
                if (position.minus(waypoint).lengthSquared() < 9) {
                    if (liveTarget != null) {
                        impact = liveTarget;
                        exit = impact.add(direction.scale(18));
                    }
                    phase = Phase.CHARGE;
                }
                return waypoint;
            case CHARGE:
                if (position.minus(impact).dot(direction) > 2) phase = Phase.PASS;
                return exit;
            case PASS:
                if (position.minus(exit).lengthSquared() < 9) beginTurn(position);
                return exit;
            case TURN:
                double[] arc = TacticalMath.arc(direction.x, direction.z, 6, turnStep * Math.PI / 4, turnSign);
                Point turnPoint = turnOrigin.add(new Point(arc[0], arc[1]));
                if (position.minus(turnPoint).lengthSquared() < 4 && ++turnStep > 4) phase = Phase.REFORM;
                return turnPoint;
            case REFORM:
                if (liveTarget == null) return position;
                prepare(position, liveTarget);
                return phase == Phase.APPROACH ? approach : exit;
            default: throw new IllegalStateException("Unknown cavalry phase");
        }
    }
    /** Returns true when repeated obstruction calls for a pause before replanning. */
    public boolean blocked(Point position) {
        if (phase == Phase.TURN) { phase = Phase.REFORM; return true; }
        beginTurn(position);
        return false;
    }
    private void beginTurn(Point position) {
        phase = Phase.TURN; turnOrigin = position; turnStep = 1;
    }
}
