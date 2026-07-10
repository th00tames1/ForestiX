// Measurement result types — port of iOS Models/MeasurementResults.swift
// (spec §6.3; DBHResult §7.1, HeightResult §7.2, PlotCenterResult §7.3).
//
// On Android two of the three transient result types already live in the
// sensor layer (ported alongside their estimators) and are NOT redefined
// here to keep one source of truth:
//   - DBHResult    -> com.hcjeong.forestix.sensors.DBHResult (DbhEstimator.kt)
//   - HeightResult -> com.hcjeong.forestix.sensors.HeightResult (HeightEstimator.kt)
// This file adds the remaining PlotCenterResult, which flows from the
// positioning layer up to the plot view-model. It is not persisted directly
// (persisted state lives on Plot).

package com.hcjeong.forestix.data.cruise

// MARK: - §7.3 PlotCenterResult

data class PlotCenterResult(
    val lat: Double,
    val lon: Double,
    val source: PositionSource,
    val tier: PositionTier,
    val nSamples: Int,
    val medianHAccuracyM: Float,
    val sampleStdXyM: Float,
    val offsetWalkM: Float?,
)
