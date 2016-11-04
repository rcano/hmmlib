package org.ialab.hmm

import scala.util.Random

/**
 * Created by hselvaggi on 17/8/16.
 * @param states A value that represent the number of states in the chain
 * @param outputs Specify how many different output values can exist
 * @param initialStates An initial estimatino of the initial state probabilities
 */
class HMM(val states: Int, val outputs: Int, val initialStates: Array[Float],
          val stateTransition: FloatMatrix /*Observations x States*/,
          val symbolOutput: FloatMatrix /*States x Observations*/) {
  import HMM._
  require(states > 0, "The state space needs to have at least 1 state")
  require(outputs > 0, "There needs to be at least one possible output symbol")

  private def fusionableForward(forwardMatrix: FloatMatrix, observations: Array[Int]): FloatMatrix.SpecializedFunction3[Int, Int, Float, Float] = { (finalState, currentTime, v) =>
    if (currentTime > 0) {
      val sym = symbolOutput(finalState, observations(currentTime))
      ∑(1, states)(i =>
        stateTransition(i, finalState) * forwardMatrix(i, currentTime - 1)) * sym
    } else initialStates(finalState) * symbolOutput(finalState, observations(0))
  }

  private def fusionableBackward(backwardMatrix: FloatMatrix, observations: Array[Int]): FloatMatrix.SpecializedFunction3[Int, Int, Float, Float] = { (prevState, currentTime, v) =>
    val time = backwardMatrix.cols - 1 - currentTime
    if (currentTime > 0) {
      ∑(0, states)(i =>
        stateTransition(prevState, i) * backwardMatrix(i, currentTime - 1) * symbolOutput(i, observations(time)))
    }
    else 1
    1
  }

  def evaluate(observations: Array[Int]) : Float = {
    val forwardMatrix = new FloatMatrix(observations.length, states)
    forwardMatrix foreachUpdate fusionableForward(forwardMatrix, observations)

    forwardMatrix.row(observations.length).sum
  }

  /**
   * Use this operation to get the sequence of states and the probability of seeing a specific sequence of
   * outputs
   * @param observations The outputs that has been observed
   * @return (probability, state sequence)
   */
  def viterbi(observations: Array[Int]): (Float, Array[Float]) = {
    val delta = new FloatMatrix(observations.length, states)
    val phsi = new FloatMatrix(observations.length, states)

    delta.foreachUpdate(rowN = 1)((y, x, _) => initialStates(x) * symbolOutput(x, observations(0)))

    delta.foreachUpdate(row0 = 1){ (time, next, _) =>
      val temp = new FloatMatrix(1, states, initialState = (_: Int, i: Int, _: Float) => delta(time-1, i) * stateTransition(i, next))
      val maxValue = temp.row(0).max
      phsi(time, next) = temp.row(0).indexOf(maxValue)
      maxValue * symbolOutput(next, observations(time))
    }

    val probability = delta.row(observations.length-1).max

    val sequence: Array[Float] = new Array[Float](observations.length)
    sequence(observations.length-1) = delta.row(observations.length-1).indexOf(probability)

    {
      var i = observations.length-1
      while ({i -= 1; i >= 0}) sequence(i) = phsi(i + 1, sequence(i+1).toInt)
    }

    (probability, sequence)
  }

  /**
   * Call this operation with a sequence of observations to learn about the different properties in the system and
   * then being able to generate or predict new sequences
   * @param observations Sequence of observations from the environment
   */
  def learn(observations: Array[Int]) = {
    val alpha = new FloatMatrix(states, observations.length)
    val beta = new FloatMatrix(states, observations.length)
    alpha foreachUpdate { (y, x, v) =>
      beta(y, x) = fusionableBackward(beta, observations)(y, x, v)
      fusionableForward(alpha, observations)(y, x, v)
    }

//    println("Alpha")
//    println(alpha.toString)
//    println("Beta")
//    println(beta.toString)

    val eta: Array[FloatMatrix] = Array.fill(observations.length)(new FloatMatrix(states, states))
    val gamma = new FloatMatrix(observations.length, states)

    for (time <- 0 until observations.length - 1) eta(time).foreachUpdate { (i, j, _) =>
      val numerator = alpha(i, time) * stateTransition(i, j) * symbolOutput(j, observations(time+1)) * beta(j, time + 1)
      val denominator = ∑(1, states)(i => beta(i, time) * alpha(i, time))

      numerator / denominator
    }

    for(time <- 0 until observations.length - 1; i <- 0 until states) {
      gamma(time, i) = eta(time).row(i).sum
    }

    stateTransition.foreachUpdate { (i, j, _) =>
      val numerator = ∑(0, observations.length) { t => eta(t)(i,j) }
      val denominator = gamma.col(i).sum
      numerator / denominator
    }

    symbolOutput.foreachUpdate { (i, o, prev) =>
      val numerator = ∑(0, observations.length) { t => if (observations(t) == o) gamma(t,i) else 0 }
      val denominator = gamma.col(i).sum
      numerator / denominator
    }
  }

  /**
   * generalization of generateOutput and nextState
   */
  private def next(probVec: Int => Float): Int = {
    val prob = Random.nextFloat()

    val (_, output) = (0 until outputs).foldLeft((0f, 0)) {
      case ((currentProb, output), newOutput) =>
        val newProb = currentProb + probVec(newOutput)
        (newProb, if (newProb < prob) newOutput else output)
    }
    output
  }


  /**
   * Once this HMM has been trained you can generate a sequence of any length with the same properties of the
   * initial sequence
   * @param sequenceLength Length of the generated sequence
   * @return A sequence of Int representing the different outputs generated from this stochastic process
   */
  def generate(sequenceLength: Int) = {
    var currentState = 0
    Array.tabulate(sequenceLength) { i =>
      val r = next(symbolOutput(currentState, _))
      currentState = next(stateTransition(currentState, _))
      r
    }
  }

}

object HMM {
  @inline def ∑(i0: Int, in: Int)(f: Int => Float): Float = {
    var acc = 0f
    for (i <- i0 until in) acc += f(i)
    acc
  }
}