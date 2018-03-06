package focusedreading.reinforcement_learning.environment

import focusedreading.Participant
import focusedreading.agents.{PolicySearchAgent, RedisSQLiteSearchAgent}
import org.sarsamora.environment.Environment
import org.sarsamora.actions.Action
import org.sarsamora.states.State
import focusedreading.agents.FocusedReadingStage
import focusedreading.reinforcement_learning.actions._
import focusedreading.reinforcement_learning.states.{FocusedReadingCompositeState, NormalizationParameters}
import org.sarsamora.policies.Policy


/**
  * Created by enrique on 30/03/17.
  */
case class SimplePathEnvironment(participantA:Participant, participantB:Participant,
                            referencePath:Seq[Participant],
                            normalizationParameters:Option[NormalizationParameters]) extends Environment {

  var rewardShapped:Int = 0
  var rewardEvaluated:Int = 0

  // TODO Refactor the code to eliminate this dummy policy
  val dummyPolicy = new Policy(){
    override def selectAction(s: State, possibleActions: Seq[Action]): Action = possibleActions.head

    override def save(path: String): Unit = Unit
  }

  val agent = new PolicySearchAgent(participantA, participantB, dummyPolicy, Some(referencePath), normalizationParameters)

  override def possibleActions(): Seq[Action] = agent.possibleActions()

  override def execute(action: Action, persist: Boolean): Double = agent.executePolicy(action, persist)

  override def observeState: State = {
//    // TODO Clean this
//    val exploreState = agent.observeExploreState(participantA, participantB, agent.triedPairs.toSet, agent.model)._2
//    val exploitState = agent.observeExploitState(participantA, participantB, agent.triedPairs.toSet, agent.model)._2
//
//    new FocusedReadingCompositeState(exploitState = exploitState, exploreState = exploreState)
    agent.observeState
  }

//  def observeStates: Seq[State] = {
//    (agent.stage: @unchecked) match {
//      case FocusedReadingStage.Query =>
//        val state = agent.observeState
//        // Repeat the state the number of different action sizes available to zip it with the possible actions
//        // This is done because the state is dependent of the action to be chosen, feels incorrect TODO: Double check this is kosher
//        Seq.fill(PolicySearchAgent.getActiveQueryActions.size)(state)
//      case FocusedReadingStage.EndPoints =>
//        // TODO: Is it valid if the state is a function of the action? Verify this is valid
//        val exploreState = agent.observeExploreState(participantA, participantB, agent.triedPairs.toSet, agent.model)._2
//        val exploitState = agent.observeExploitState(participantA, participantB, agent.triedPairs.toSet, agent.model)._2
//
//        val actions = PolicySearchAgent.getActiveEndpointActions.toSeq
//
//        actions.map{
//          case _:ExploreEndpoints =>
//            exploreState
//          case _:ExploitEndpoints =>
//            exploitState
//        }
//    }
//  }

  override def finishedEpisode:Boolean ={
    val ret = agent.hasFinished(participantA, participantB, agent.model, false)

    // TODO: Clean this up or remove
    if(ret){
      rewardShapped = agent.shapingCount
      rewardEvaluated = agent.rewardEvaluated
    }
    ////////////////////////////////

    ret
  }

}
