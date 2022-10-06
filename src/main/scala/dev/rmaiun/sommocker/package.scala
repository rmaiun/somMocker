package dev.rmaiun

package object sommocker {
  val ControlTopic = "OptimizationControl_$algorithm";
  val ResultQueue = "OptimizationResult_$algorithm"
  val StatusQueue = "OptimizationStatus_$algorithm"
}
