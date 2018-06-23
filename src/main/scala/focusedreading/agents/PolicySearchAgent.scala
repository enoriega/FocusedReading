package focusedreading.agents

import focusedreading.entities.{Connection, Participant}
import focusedreading.{Configuration}
import focusedreading.entities.Connection
import focusedreading.ie.SQLIteIEStrategy
import focusedreading.ir.queries.{Query, QueryStrategy}
import focusedreading.ir.queries.QueryStrategy.{Conjunction, Disjunction}
import focusedreading.ir.RedisIRStrategy
import focusedreading.search_models.{EfficientSearchModel, SearchModel}
import focusedreading.pc_strategies.PolicyParticipantsStrategy
import focusedreading.reinforcement_learning.actions._
import focusedreading.reinforcement_learning.states.{FocusedReadingState, NormalizationParameters}
import org.sarsamora.actions.Action
import org.sarsamora.policies.Policy
import org.sarsamora.states.State

import scala.collection.mutable


/**
  * Search agent that follows a policy, presumably learnt using RL.
  * Look at the traits to see which strategies it follows
  * @param participantA Origin of the search
  * @param participantB Destination of the search
  * @param policy Policy to follow
  * @param referencePath Expert path to compute the reward shaping potential
  */
class PolicySearchAgent(val participantA:Participant, val participantB:Participant,
                        val policy:Option[Policy] = None,
                        val referencePath:Option[Seq[Participant]] = None,
                        val normalizationParameters:Option[NormalizationParameters] = None)
                       (implicit indexPath:LuceneIndexDir, sqliteFile: SQLiteFile)
  extends SimplePathAgent(participantA, participantB) with Serializable

  with PolicyParticipantsStrategy
  with RedisIRStrategy
  with SQLIteIEStrategy {


  /***
    * Amount of papers to read associated with a particular action
    * @param a Action to inspect
    * @return Upper bountd to the number of papers to be read by a
    */
  def paperAmountFor(a:FocusedReadingAction):Int = a match {
    case ExploreEndpoints_ExploitQuery => this.fewPapers
    case ExploreEndpoints_ExploreManyQuery => this.manyPapers
    case ExploreEndpoints_ExploreFewQuery => this.fewPapers
    case ExploitEndpoints_ExploitQuery => this.fewPapers
    case ExploitEndpoints_ExploreManyQuery => this.manyPapers
    case ExploitEndpoints_ExploreFewQuery => this.fewPapers
    case _ => throw new RuntimeException(s"Paper amount for $a not defined yet")
  }


  override def clone():PolicySearchAgent = {
    val clone = new PolicySearchAgent(participantA, participantB, policy, referencePath, normalizationParameters)(this.indexPath, this.sqliteFile)

    clone.model = this.model.copy()
    clone.actionCounters ++= this.actionCounters
    clone.nodesCount = this.nodesCount
    clone.edgesCount = this.edgesCount
    clone.prevNodesCount = this.prevNodesCount
    clone.prevEdgesCount = this.prevEdgesCount
    clone.unchangedIterations = this.unchangedIterations

    clone.iterationNum = this.iterationNum
    clone.triedPairs = this.triedPairs
    clone.papersRead = this.papersRead
    //clone.trace ++= this.trace

    clone.references = this.references
    clone.queryLog = this.queryLog
    clone.introductions = this.introductions
    clone.lastActionChosen = this.lastActionChosen
    clone.chosenEndpointsLog = this.chosenEndpointsLog
    clone.colors = this.colors

    clone
  }

  lazy val indexDir: String = indexPath.path
  lazy val sqlitePath: String = sqliteFile.path

  // Fields
  val actionCounters: mutable.Map[String, Int] = new mutable.HashMap[String, Int]() ++ PolicySearchAgent.activeActions.map(_.toString -> 0).toMap


  this.introductions += participantA -> 0
  this.introductions += participantB -> 0

  private val useRewardShaping = Configuration.MDP.useRewardShaping
  private val fewPapers = Configuration.MDP.PaperAmounts.few
  private val manyPapers = Configuration.MDP.PaperAmounts.many

  var shapingCount:Int = 0
  var rewardEvaluated:Int = 0
  ////////////

  override def choseEndPoints(source: Participant, destination: Participant,
                              previouslyChosen: Set[(Participant, Participant)],
                              model: SearchModel): (Participant, Participant) = {

    // Choose the endpoints with the policy
    val endpoints = super.choseEndPoints(source, destination, previouslyChosen, model)

    // Keep track of the chosen actions
    actionCounters(this.lastActionChosen.get.toString) += 1


    endpoints
  }

  /*override var*/ model/*:SearchModel*/ = new EfficientSearchModel(participantA, participantB) // Directed graph with the model.

  override def reconcile(connections: Iterable[Connection]): Unit = {
    // Count the introductions
    for(f <- connections){
      val x = f.controller
      val y = f.controlled

      val sign = f.sign

      // Store the references
      references += (x, y, sign) -> f.reference


      if(!introductions.contains(x))
        introductions += (x -> iterationNum)

      if(!introductions.contains(y))
        introductions += (y -> iterationNum)


    }

    super.reconcile(connections)
  }


  override def choseQuery(a: Participant,
                          b: Participant,
                          model: SearchModel): Query = policy match {

    case Some(p) =>
      queryLog = (a, b)::queryLog

      val possibleActions: Seq[Action] = PolicySearchAgent.activeActions

      // Create state
      val state = this.observeState

      // Query the policy
      val action = p.selectAction(state, possibleActions)

      // Keep track of the action selection
      actionCounters(action.toString) += 1


      queryActionToStrategy(action, a, b)
    case None => throw new IllegalStateException("This agent wasn't provided with a policy")
  }

  override def observeState:State = {
    fillState(this.model, iterationNum, queryLog, introductions)
  }

  override def getIterationNum: Int = iterationNum

  // Auxiliary methods
  private def fillState(model:SearchModel, iterationNum:Int,
                        queryLog:Seq[(Participant, Participant)],
                        introductions:Map[Participant, Int]):State = {

    val searchGraphElements = this.model.edges.map{
      conn =>
        (conn.controller.id, conn.controlled.id, conn.sign)
    }.toSet

    if(queryLog.nonEmpty) {



      val (a, b) = queryLog.last
      val log = queryLog flatMap (l => Seq(l._1, l._2))
      val paQueryLogCount = log.count(p => p == a)
      val pbQueryLogCount = log.count(p => p == b)

      val few = this.fewPapers
      val many = this.manyPapers

      val exploreFewQuery = Query(QueryStrategy.Disjunction, few, a, Some(b))
      val exploreManyQuery = Query(QueryStrategy.Disjunction, many, a, Some(b))
      val exploitQuery = Query(QueryStrategy.Conjunction, few, a, Some(b))

      val exploreFewIRScores = (this.informationRetrieval(exploreFewQuery) map (_._2)).toSeq
      val exploreManyIRScores = (this.informationRetrieval(exploreManyQuery) map (_._2)).toSeq
      val exploitIRScores = (this.informationRetrieval(exploitQuery) map (_._2)).toSeq


      val compA = model.getConnectedComponentOf(a).get
      val compB = model.getConnectedComponentOf(b).get

      val sameComponent = compA == compB

      val paIntro = introductions(a)
      val pbIntro = introductions(b)

      val ranks: Map[Participant, Int] = model.rankedNodes

      val paRank = (ranks(a) + 1) / model.numNodes.toDouble //getRank(a, ranks)
      val pbRank = (ranks(b) + 1) / model.numNodes.toDouble //getRank(b, ranks)

      val paUngrounded = a.id.toUpperCase.startsWith("UAZ")
      val pbUngrounded = b.id.toUpperCase.startsWith("UAZ")

      if(paRank < 0 || paRank > 1) println("PA rank is out of bounds")
      if(pbRank < 0 || pbRank > 1) println("PB rank is out of bounds")

      FocusedReadingState(participantA.id, participantB.id, searchGraphElements, paRank, pbRank, iterationNum, paQueryLogCount,
        pbQueryLogCount, sameComponent, paIntro, pbIntro, paUngrounded,
        pbUngrounded, exploreFewIRScores, exploreManyIRScores,
        exploitIRScores, unchangedIterations)
    }
    else{

      FocusedReadingState(participantA.id, participantB.id, searchGraphElements, 0, 0, iterationNum, 0,
        0, sameComponent = false, 0, 0, paUngrounded = false,
        pbUngrounded = false, Seq(0), Seq(0),
        Seq(0), 0)
    }
  }

  // This is an optimization to avoid running shortest paths every time
  // Refer to https://people.eecs.berkeley.edu/~pabbeel/cs287-fa09/readings/NgHaradaRussell-shaping-ICML1999.pdf
  // For the theoretical justification of this reward shaping set up
  private val shapingCache = new mutable.HashSet[(Participant, Participant)]


  /**
    * Computes the reward shaping potential
    * @return Reward shaping potential for the current state of the search
    */
  private def shapingPotential:Double = {

    referencePath match {

      case Some(path) =>
        // If the path is only a pair, return zero
        if(path.size == 2)
          0.0
        else{
          // Get the hops in the reference path
          val pairs = path.sliding(2).toList // (a, b, c) => (a, b), (b, c)
          var segmentsFound = 0 // Counter of the number of segments in the model
          var flag = true // Shortcut flag to break the loop
          val nodeSet = model.nodes.toSet // Set of nodes currently in the search graph

          // Iterate through every pair of participants in the reference path to find a path among them
          for(pair <- pairs){
            val (a, b) = (pair.head, pair(1))
            // If the previous segment wasn't found, then not continue
            if(flag){
              // Test whether the destination exists in the model
              if(nodeSet.contains(b)){
                // If it exists, see if there's a directed path between the nodes
                // First check the cache
                if(shapingCache.contains((a, b))){
                  segmentsFound +=1
                }
                // Then run shortest path in the model
                else{
                  val segment = model.shortestPath(a, b)
                  // If found, cache it
                  if(segment.isDefined){
                    segmentsFound += 1
                    shapingCache += ((a, b))
                  }
                  // If not, then shortcut the potential
                  else{
                    flag = false
                  }
                }

              }
              else{
                flag = false
              }
            }
          }

          // The potential is the proportions of segments in the reference path found in the model
          segmentsFound / pairs.size
        }

      case None => 0.0
    }
  }

  private def queryActionToStrategy(action: Action, a: Participant, b: Participant) = {

    action match {
      case ac if Seq(ExploitEndpoints_ExploitQuery, ExploreEndpoints_ExploitQuery).contains(ac) =>
        Query(Conjunction, fewPapers, a, Some(b))
      case ac if Seq(ExploitEndpoints_ExploreManyQuery, ExploreEndpoints_ExploreManyQuery).contains(ac) =>
        Query(Disjunction, manyPapers, a, Some(b))
      case ac if Seq(ExploitEndpoints_ExploreFewQuery, ExploreEndpoints_ExploreFewQuery).contains(ac) =>
        Query(Disjunction, manyPapers, a, Some(b))
      case _ =>
        throw new RuntimeException("Got an invalid action type for the query stage")
    }
  }


  // Public methods
  def executePolicy(action:Action, persist:Boolean = true):Double =  {
    if(persist)
      iterationNum += 1

    val selectedChooser = action match {
      case ac if Seq(ExploitEndpoints_ExploitQuery, ExploitEndpoints_ExploreManyQuery, ExploitEndpoints_ExploreFewQuery).contains(ac) => exploitChooser
      case ac if Seq(ExploreEndpoints_ExploitQuery, ExploreEndpoints_ExploreManyQuery, ExploreEndpoints_ExploreFewQuery).contains(ac) => exploreChooser
      case _ => throw new RuntimeException("Invalid action for the ENDPOINTS stage")
    }

    val (a, b) = selectedChooser.choseEndPoints(participantA, participantB, triedPairs, model)
    ////////


    if(persist){
      triedPairs += Tuple2(a, b)
      queryLog = (a, b)::queryLog
    }


    // Compute the reward shaping potential in the current state
    val prevPotential = if (useRewardShaping) {
      shapingPotential
    } else {
      0.0
    }


    // Build a query object based on the action
    val query = queryActionToStrategy(action, a, b)

    val paperIds = this.informationRetrieval(query)

    this.papersRead ++= paperIds map (_._1.intern())

    val findings = this.informationExtraction(paperIds map (p => p._1.intern()))

    // Count the introductions
    for(f <- findings){
      val x = f.controller
      val y = f.controlled

      if(persist){
        if(!introductions.contains(x))
          introductions += (x -> iterationNum)

        if(!introductions.contains(y))
          introductions += (y -> iterationNum)
      }

    }

    // Add the stuff to the model
    reconcile(findings)



    // Compute the reward shaping, if on
    val currentPotential = if (useRewardShaping) {
      shapingPotential
    } else {
      0.0
    }

    // Reward shaping function (potential difference)
    val rewardShapigCoefficient = Configuration.MDP.rewardShapingCoefficient
    val shaping = rewardShapigCoefficient*currentPotential - prevPotential


    // TODO: Delete me
    if(shaping > 0) {
      shapingCount += 1
    }

    rewardEvaluated += 1
    /////////////////
    // Return the observed reward
    if(!this.hasFinished(participantA, participantB, model, mutate = true)){
      // If this episode hasn't finished
      // TODO: Parameterize the reward structure
      -0.05 + shaping
    }
    else{
      // If finished successfully
      val uniquePapers = this.papersRead.toSet.size
      successStopCondition(participantA, participantB, model) match{
        case Some(p) =>
          10.0 + shaping
        case None =>
          -1.0 + shaping
      }
    }

  }

  def possibleActions: Seq[Action] = PolicySearchAgent.activeActions
  /////////////////


}


/**
  * Companion object.
  */
object PolicySearchAgent {
  // TODO Factor out these members into another object. The agent is not the right place for them
  private val elements = Configuration.MDP.activeActions.toSet map FocusedReadingAction.apply
  lazy val activeActions: Seq[FocusedReadingAction] = FocusedReadingAction.allActions filter elements.contains
}

