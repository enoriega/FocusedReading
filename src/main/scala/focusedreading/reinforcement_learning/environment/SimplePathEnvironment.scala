package org.clulab.reach.focusedreading.reinforcement_learning.environment

import org.clulab.reach.focusedreading.Participant
import org.clulab.focusedreading.agents.{PolicySearchAgent, RedisSQLiteSearchAgent}
import org.sarsamora.environment.Environment
import org.sarsamora.actions.Action
import org.sarsamora.policies.DummyPolicy
import org.sarsamora.states.State
import org.clulab.focusedreading.agents.FocusedReadingStage
import org.clulab.reach.focusedreading.reinforcement_learning.actions._


/**
  * Created by enrique on 30/03/17.
  */
class SimplePathEnvironment(participantA:Participant, participantB:Participant) extends Environment {

  val agent = new PolicySearchAgent(participantA, participantB, DummyPolicy())


  override def possibleActions(): Seq[Action] = agent.possibleActions()

  override def executePolicy(action: Action, persist: Boolean): Double = agent.executePolicy(action, persist)

  override def observeState: State = agent.observeState

  override def observeStates: Seq[State] = {
    (agent.stage: @unchecked) match {
      case FocusedReadingStage.Query =>
        val state = agent.observeState
        Seq(state, state)
      case FocusedReadingStage.EndPoints =>
        val exploreState = agent.observeExploreState(participantA, participantB, agent.triedPairs.toSet, agent.model)._2
        val exploitState = agent.observeExploitState(participantA, participantB, agent.triedPairs.toSet, agent.model)._2

        val actions = possibleActions()

        actions.map{
          case _:ExploreEndpoints =>
            exploreState
          case _:ExploitEndpoints =>
            exploitState
        }
    }
  }

  override def finishedEpisode:Boolean = agent.stage == FocusedReadingStage.EndPoints && agent.hasFinished(participantA, participantB, agent.model)

}
