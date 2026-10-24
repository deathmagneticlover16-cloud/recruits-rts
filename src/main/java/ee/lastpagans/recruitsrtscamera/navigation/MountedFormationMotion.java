package ee.lastpagans.recruitsrtscamera.navigation;

/** Dependency-free damped velocity math for an RTS-controlled formation mount. */
public final class MountedFormationMotion {
    private MountedFormationMotion() { }

    public static Vector velocity(
            double currentX,
            double currentZ,
            double errorX,
            double errorZ,
            double formationX,
            double formationZ,
            double catchUpScale
    ) {
        double formationSpeed = Math.hypot(formationX, formationZ);
        // Knockback, a navigation handoff or another mod can occasionally leave one mount
        // with a many-block-per-second horizontal impulse. Do not blend that exceptional
        // velocity into slot correction and fling the horse through the formation.
        double currentSpeed = Math.hypot(currentX, currentZ);
        // Keep this well above every intentional walk/run/charge speed. The previous
        // low clamp treated healthy gallop momentum as an error, made riders lag, then
        // caused cohesion recovery to fight them around ledges.
        double retainedLimit = Math.max(1.25D, formationSpeed + 0.75D);
        if (currentSpeed > retainedLimit) {
            currentX *= retainedLimit / currentSpeed;
            currentZ *= retainedLimit / currentSpeed;
        }
        double slotDistance = Math.hypot(errorX, errorZ);
        double safeScale = clamp(catchUpScale, 1.0D, 1.25D);
        double requestedX;
        double requestedZ;
        if (formationSpeed > 1.0E-5D) {
            double travelX = formationX / formationSpeed;
            double travelZ = formationZ / formationSpeed;
            double alongError = errorX * travelX + errorZ * travelZ;
            double alongCurrent = currentX * travelX + currentZ * travelZ;
            double lateralErrorX = errorX - travelX * alongError;
            double lateralErrorZ = errorZ - travelZ * alongError;
            double lateralCurrentX = currentX - travelX * alongCurrent;
            double lateralCurrentZ = currentZ - travelZ * alongCurrent;
            double along = formationSpeed + clamp(alongError * 0.10D, -0.07D, 0.11D);
            double lateralX = lateralErrorX * 0.075D - lateralCurrentX * 0.45D;
            double lateralZ = lateralErrorZ * 0.075D - lateralCurrentZ * 0.45D;
            double lateralLength = Math.hypot(lateralX, lateralZ);
            double lateralLimit = slotDistance > 1.5D ? 0.065D : 0.032D;
            if (lateralLength > lateralLimit) {
                lateralX *= lateralLimit / lateralLength;
                lateralZ *= lateralLimit / lateralLength;
            }
            requestedX = travelX * Math.max(0.04D, along) + lateralX;
            requestedZ = travelZ * Math.max(0.04D, along) + lateralZ;
        } else if (slotDistance > 1.0E-5D) {
            double correction = Math.min(0.16D * safeScale, slotDistance * 0.16D);
            requestedX = errorX / slotDistance * correction;
            requestedZ = errorZ / slotDistance * correction;
        } else {
            requestedX = 0.0D;
            requestedZ = 0.0D;
        }

        double maximum = Math.max(0.16D, formationSpeed + 0.11D) * safeScale;
        double requestedLength = Math.hypot(requestedX, requestedZ);
        if (requestedLength > maximum) {
            requestedX *= maximum / requestedLength;
            requestedZ *= maximum / requestedLength;
        }
        return new Vector(
                currentX * 0.30D + requestedX * 0.70D,
                currentZ * 0.30D + requestedZ * 0.70D
        );
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Vector(double x, double z) { }
}
