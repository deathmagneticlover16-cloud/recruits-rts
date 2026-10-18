package ee.lastpagans.recruitsrtscamera.formation;

/**
 * Describes how a formation plan was authored.
 * PRESET is the G-cycled native formation shape.
 * FRONTAGE is an RTS drag line with explicit width/facing/depth.
 * CUSTOM is an Alt+RMB freehand stroke executed by RTS Camera's custom-slot engine.
 */
public enum FormationMode {
    PRESET,
    FRONTAGE,
    CUSTOM
}
