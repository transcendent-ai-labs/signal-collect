/*
 *  @author Philip Stutz
 *  @author Francisco de Freitas
 *  @author Daniel Strebel
 *  
 *  
 *  Copyright 2012 University of Zurich
 *      
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 */

package com.signalcollect.implementations.worker

import com.signalcollect.configuration._
import java.util.concurrent.TimeUnit
import com.signalcollect.implementations._
import com.signalcollect.interfaces._
import java.util.concurrent.BlockingQueue
import java.util.HashSet
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.LinkedHashMap
import java.util.Map
import java.util.Set
import com.signalcollect._
import com.signalcollect.implementations.serialization.DefaultSerializer
import akka.util.duration._
import akka.actor.PoisonPill
import akka.actor.ReceiveTimeout
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.event.LoggingReceive

class WorkerOperationCounters(
  var messagesReceived: Long = 0l,
  var collectOperationsExecuted: Long = 0l,
  var signalOperationsExecuted: Long = 0l,
  var verticesAdded: Long = 0l,
  var verticesRemoved: Long = 0l,
  var outgoingEdgesAdded: Long = 0l,
  var outgoingEdgesRemoved: Long = 0l,
  var incomingEdgesAdded: Long = 0l,
  var incomingEdgesRemoved: Long = 0l,
  var signalSteps: Long = 0l,
  var collectSteps: Long = 0l)

class AkkaWorker(val workerId: Int,
  config: GraphConfiguration)
  extends Worker with ActorLogging {

  override def toString = "Worker" + workerId

  val loggingLevel = config.loggingLevel

  val messageBus: MessageBus = {
    config.messageBusFactory.createInstance(config.numberOfWorkers)
  }

  /**
   * Timeout for Akka actor idling
   */
  context.setReceiveTimeout(5 milliseconds)

  def isInitialized = messageBus.isInitialized

  /**
   * This method gets executed when the Akka actor receives a message.
   */
  def receive = {
    
    case PoisonPill =>
      shutdown

    /**
     * ReceiveTimeout message only gets sent after Akka actor mailbox has been empty for "receiveTimeout" milliseconds
     */
    case ReceiveTimeout =>
      if (isConverged || isPaused) { // if the actor has nothing to compute and the mailbox is empty, then it is idle
        setIdle(true)
      } else {
        handlePauseAndContinue
        performComputations
      }

    case msg =>
      setIdle(false)
      process(msg) // process the message
      handlePauseAndContinue
      performComputations
  }

  //  /**
  //   * Tests if the actor mailbox is empty
  //   */
  //  def isMailboxEmpty: Boolean = {
  //    !AkkaMailbox.mailboxes.get(context).hasMessages
  //  }

  val mailbox = context.asInstanceOf[{ def mailbox: { def canBeScheduledForExecution(a: Boolean, b: Boolean): Boolean } }].mailbox

  /**
   * Tests if the actor mailbox is empty
   */
  def isMailboxEmpty: Boolean = {
    // See http://groups.google.com/group/akka-user/browse_thread/thread/6e645ee0c242c95b/c082f33e917db656 last post by Viktor Klang
    !mailbox.canBeScheduledForExecution(false, false)
  }

  def performComputations {
    if (config.statusUpdateIntervalInMillis.isDefined) {
      val currentTime = System.currentTimeMillis
      if (currentTime - lastStatusUpdate > updateInterval) {
        lastStatusUpdate = currentTime
        sendStatusToCoordinator
      }
    }
    if (!isPaused) {
      while (isMailboxEmpty && !isConverged) {
        vertexStore.toSignal.foreach(executeSignalOperationOfVertex(_), removeAfterProcessing = true)
        vertexStore.toCollect.foreach(
          (vertexId, uncollectedSignals) => {
            val collectExecuted = executeCollectOperationOfVertex(vertexId, uncollectedSignals, addToSignal = false)
            if (collectExecuted) {
              executeSignalOperationOfVertex(vertexId)
            }
          }, removeAfterProcessing = true, breakCondition = () => !isMailboxEmpty)
      }
    }

  }

  protected val counters = new WorkerOperationCounters()
  protected val graphEditor: GraphEditor = messageBus.getGraphEditor
  protected var undeliverableSignalHandler: (SignalMessage[_, _, _], GraphEditor) => Unit = (s, g) => {}

  protected val updateInterval: Long = config.statusUpdateIntervalInMillis.getOrElse(Long.MaxValue)

  protected def process(message: Any) {
    counters.messagesReceived += 1
    message match {
      case s: SignalMessage[_, _, _] => {
        processSignal(s)
      }
      case Request(command, reply) => {
        val result = command(this)
        if (reply) {
          messageBus.getSentMessagesCounter.incrementAndGet
          sender ! result
        }
      }
      case other => warning("Could not handle message " + message)
    }
  }

  def addVertex(serializedVertex: Array[Byte]) {
    val vertex = DefaultSerializer.read[Vertex](serializedVertex)
    addVertex(vertex)
  }

  def addOutgoingEdge(serializedEdge: Array[Byte]) {
    val edge = DefaultSerializer.read[Edge](serializedEdge)
    addOutgoingEdge(edge)
  }

  def addIncomingEdge(serializedEdge: Array[Byte]) {
    val edge = DefaultSerializer.read[Edge](serializedEdge)
    addIncomingEdge(edge)
  }

  def addVertex(vertex: Vertex) {
    if (vertexStore.vertices.put(vertex)) {
      counters.verticesAdded += 1
      counters.outgoingEdgesAdded += vertex.outgoingEdgeCount
      try {
        vertex.afterInitialization(messageBus)
      } catch {
        case t: Throwable => severe(t)
      }
      if (vertex.scoreSignal > signalThreshold) {
        vertexStore.toSignal.add(vertex.id)
      }
    }
  }

  def addOutgoingEdge(edge: Edge) {
    val key = edge.id.sourceId
    val vertex = vertexStore.vertices.get(key)
    if (vertex != null) {
      if (vertex.addOutgoingEdge(edge)) {
        counters.outgoingEdgesAdded += 1
        vertexStore.toCollect.addVertex(vertex.id)
        vertexStore.toSignal.add(vertex.id)
        vertexStore.vertices.updateStateOfVertex(vertex)
        val request = Request[Worker]((_.addIncomingEdge(edge)), returnResult = false)
        messageBus.sendToWorkerForVertexId(request, edge.id.targetId)
      }
    } else {
      warning("Did not find vertex with id " + edge.id.sourceId + " when trying to add outgoing edge " + edge)
    }
  }

  def addIncomingEdge(edge: Edge) {
    val key = edge.id.targetId
    val vertex = vertexStore.vertices.get(key)
    if (vertex != null) {
      if (vertex.addIncomingEdge(edge)) {
        counters.incomingEdgesAdded += 1
        vertexStore.vertices.updateStateOfVertex(vertex)
      }
    } else {
      warning("Did not find vertex with id " + edge.id.sourceId + " when trying to add incoming edge " + edge)
    }
  }

  def addPatternEdge(sourceVertexPredicate: Vertex => Boolean, edgeFactory: Vertex => Edge) {
    vertexStore.vertices foreach { vertex =>
      if (sourceVertexPredicate(vertex)) {
        addOutgoingEdge(edgeFactory(vertex))
      }
    }
  }

  def removeVertex(vertexId: Any) {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      processRemoveVertex(vertex)
    } else {
      warning("Should remove vertex with id " + vertexId + ": could not find this vertex.")
    }
  }

  def removeOutgoingEdge(edgeId: EdgeId[Any, Any]) {
    val vertex = vertexStore.vertices.get(edgeId.sourceId)
    if (vertex != null) {
      if (vertex.removeOutgoingEdge(edgeId)) {
        counters.outgoingEdgesRemoved += 1
        val request = Request[Worker]((_.removeIncomingEdge(edgeId)))
        messageBus.sendToWorkerForVertexId(request, edgeId.targetId)
        vertexStore.toCollect.addVertex(vertex.id)
        vertexStore.toSignal.add(vertex.id)
        vertexStore.vertices.updateStateOfVertex(vertex)
      } else {
        warning("Outgoing edge not found when trying to remove edge with id " + edgeId)
      }
    } else {
      warning("Source vertex not found found when trying to remove outgoing edge with id " + edgeId)
    }
  }

  def removeIncomingEdge(edgeId: EdgeId[Any, Any]) {
    val vertex = vertexStore.vertices.get(edgeId.targetId)
    if (vertex != null) {
      if (vertex.removeIncomingEdge(edgeId)) {
        counters.incomingEdgesRemoved += 1
        vertexStore.vertices.updateStateOfVertex(vertex)
      } else {
        warning("Incoming edge not found when trying to remove edge with id " + edgeId)
      }
    } else {
      warning("Source vertex not found found when trying to remove incoming edge with id " + edgeId)
    }
  }

  def removeVertices(shouldRemove: Vertex => Boolean) {
    vertexStore.vertices foreach { vertex =>
      if (shouldRemove(vertex)) {
        processRemoveVertex(vertex)
      }
    }
  }

  protected def processRemoveVertex(vertex: Vertex) {
    counters.outgoingEdgesRemoved += vertex.outgoingEdgeCount
    val edgesRemoved = vertex.removeAllOutgoingEdges
    counters.outgoingEdgesRemoved += edgesRemoved
    counters.verticesRemoved += 1
    vertex.beforeRemoval(messageBus)
    vertexStore.vertices.remove(vertex.id)
  }

  def setUndeliverableSignalHandler(h: (SignalMessage[_, _, _], GraphEditor) => Unit) {
    undeliverableSignalHandler = h
  }

  def setSignalThreshold(st: Double) {
    signalThreshold = st
  }

  def setCollectThreshold(ct: Double) {
    collectThreshold = ct
  }

  def recalculateScores {
    vertexStore.vertices.foreach(recalculateVertexScores(_))
  }

  def recalculateScoresForVertexWithId(vertexId: Any) {
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      recalculateVertexScores(vertex)
    }
  }

  protected def recalculateVertexScores(vertex: Vertex) {
    vertexStore.toCollect.addVertex(vertex.id)
    vertexStore.toSignal.add(vertex.id)
  }

  def forVertexWithId[VertexType <: Vertex, ResultType](vertexId: Any, f: VertexType => ResultType): Option[ResultType] = {
    var result: Option[ResultType] = None
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      try {
        result = Some(f(vertex.asInstanceOf[VertexType]))
        vertexStore.vertices.updateStateOfVertex(vertex)
      }
    }
    result
  }

  def foreachVertex(f: Vertex => Unit) {
    vertexStore.vertices.foreach(f)
  }

  def aggregate[ValueType](aggregationOperation: AggregationOperation[ValueType]): ValueType = {
    var acc = aggregationOperation.neutralElement
    vertexStore.vertices.foreach { vertex => acc = aggregationOperation.aggregate(acc, aggregationOperation.extract(vertex)) }
    acc
  }

  def startAsynchronousComputation {
    shouldStart = true
  }

  def pauseAsynchronousComputation {
    shouldPause = true
  }

  def signalStep {
    counters.signalSteps += 1
    vertexStore.toSignal foreach (executeSignalOperationOfVertex(_), true)
  }

  def collectStep: Boolean = {
    counters.collectSteps += 1
    vertexStore.toCollect foreach ((vertexId, uncollectedSignalsList) => {
      executeCollectOperationOfVertex(vertexId, uncollectedSignalsList)
    }, true)
    vertexStore.toSignal.isEmpty
  }

  def getWorkerStatistics: WorkerStatistics = {
    WorkerStatistics(
      messagesReceived = counters.messagesReceived,
      messagesSent = messageBus.messagesSent,
      collectOperationsExecuted = counters.collectOperationsExecuted,
      signalOperationsExecuted = counters.signalOperationsExecuted,
      numberOfVertices = vertexStore.vertices.size,
      verticesAdded = counters.verticesAdded,
      verticesRemoved = counters.verticesRemoved,
      numberOfOutgoingEdges = counters.outgoingEdgesAdded - counters.outgoingEdgesRemoved, //only valid if no edges are removed during execution
      outgoingEdgesAdded = counters.outgoingEdgesAdded,
      outgoingEdgesRemoved = counters.outgoingEdgesRemoved)
  }

  def shutdown {
    vertexStore.cleanUp
  }

  protected var shouldShutdown = false
  protected var isIdle = false
  protected var isPaused = true
  protected var shouldPause = false
  protected var shouldStart = false

  protected var signalThreshold = 0.001
  protected var collectThreshold = 0.0

  protected var lastStatusUpdate = System.currentTimeMillis

  protected val vertexStore = config.storageFactory.createInstance

  protected def isConverged = vertexStore.toCollect.isEmpty && vertexStore.toSignal.isEmpty

  protected def getWorkerStatus: WorkerStatus = {
    WorkerStatus(
      workerId = workerId,
      isIdle = isIdle,
      isPaused = isPaused,
      messagesSent = messageBus.messagesSent,
      messagesReceived = counters.messagesReceived)
  }

  protected def sendStatusToCoordinator {
    val status = getWorkerStatus
    messageBus.sendToCoordinator(status)
  }

  protected def setIdle(newIdleState: Boolean) {
    if (isIdle != newIdleState) {
      if (isInitialized) {
        isIdle = newIdleState
        sendStatusToCoordinator
      }
    }
  }

  protected def executeCollectOperationOfVertex(vertexId: Any, uncollectedSignalsList: Iterable[SignalMessage[_, _, _]], addToSignal: Boolean = true): Boolean = {
    var hasCollected = false
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      if (vertex.scoreCollect(uncollectedSignalsList) > collectThreshold) {
        try {
          counters.collectOperationsExecuted += 1
          vertex.executeCollectOperation(uncollectedSignalsList, messageBus)
          vertexStore.vertices.updateStateOfVertex(vertex)
          hasCollected = true
          if (addToSignal && vertex.scoreSignal > signalThreshold) {
            vertexStore.toSignal.add(vertex.id)
          }
        } catch {
          case t: Throwable => severe(t)
        }
      }
    } else {
      uncollectedSignalsList.foreach(undeliverableSignalHandler(_, graphEditor))
    }
    hasCollected
  }

  protected def executeSignalOperationOfVertex(vertexId: Any): Boolean = {
    var hasSignaled = false
    val vertex = vertexStore.vertices.get(vertexId)
    if (vertex != null) {
      if (vertex.scoreSignal > signalThreshold) {
        try {
          vertex.executeSignalOperation(messageBus)
          counters.signalOperationsExecuted += 1
          vertexStore.vertices.updateStateOfVertex(vertex)
          hasSignaled = true
        } catch {
          case t: Throwable => severe(t)
        }
      }
    }
    hasSignaled
  }

  def processSignal(signal: SignalMessage[_, _, _]) {
    debug(signal)
    vertexStore.toCollect.addSignal(signal)
  }

  def registerWorker(workerId: Int, worker: ActorRef) {
    messageBus.registerWorker(workerId, worker)
  }

  def registerCoordinator(coordinator: ActorRef) {
    messageBus.registerCoordinator(coordinator)
  }

  def registerLogger(logger: ActorRef) {
    messageBus.registerLogger(logger)
  }

  protected def handlePauseAndContinue {
    if (shouldStart) {
      shouldStart = false
      isPaused = false
      sendStatusToCoordinator
    } else if (shouldPause) {
      shouldPause = false
      isPaused = true
      sendStatusToCoordinator
    }
  }

}