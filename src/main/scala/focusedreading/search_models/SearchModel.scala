package focusedreading.search_models

import focusedreading.entities.{Connection, Participant}

/**
  * Created by enrique on 08/03/17.
  */
abstract class SearchModel {

  def this(source:Participant, destination:Participant){
    this()
    this.addNode(source)
    this.addNode(destination)
  }

  def addNode(p:Participant):Unit
  def addEdge(e:Connection):Unit
  def addEdges(es:Iterable[Connection]):Unit

  def nodes:Iterable[Participant]
  def edges:Iterable[Connection]

  def numNodes:Int
  def numEdges:Int

  def connectedComponents():Iterable[Set[Participant]]

  def getConnectedComponentOf(node:Participant):Option[Iterable[Participant]]

  def shortestPath(source:Participant, destination:Participant):Option[Seq[Connection]]

  def allPaths(source:Participant, destination:Participant):Iterable[Seq[Connection]]

  def degree(node:Participant):Int

  def rankedNodes:Map[Participant, Int]

  def copy():SearchModel
}
