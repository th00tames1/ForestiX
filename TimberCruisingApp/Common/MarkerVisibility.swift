/// Keep lit, three-dimensional sphere shading, but fill the dark hemisphere.
/// This affects display material only, never marker coordinates or measurement.
public enum MarkerVisibility {
    public static func minimumEmission(isSphere: Bool, alpha: Float) -> Float {
        isSphere && alpha >= 0.999 ? 0.45 : 0
    }
}
