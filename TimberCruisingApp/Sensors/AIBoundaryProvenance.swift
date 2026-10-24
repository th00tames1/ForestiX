import Models

/// Mode, inference history and current-bound origin are separate facts.
public enum AIBoundaryProvenance {
    public static func snapshot(enabled: Bool,usingBracket: Bool,lastInferenceId: String?,lastHadMask: Bool,
                                boundaryInferenceId: String?,userEdited: Bool) -> FieldAIProvenance {
        var ai = FieldAIProvenance(); ai.enabled = enabled
        ai.inferenceRan = lastInferenceId != nil || boundaryInferenceId != nil
        ai.boundaryApplied = usingBracket && boundaryInferenceId != nil && !userEdited
        ai.maskDetected = ai.boundaryApplied || (lastInferenceId != nil && lastHadMask)
        ai.inferenceId = ai.boundaryApplied ? boundaryInferenceId : lastInferenceId
        ai.userAdjustedBoundary = userEdited
        return ai
    }
}
