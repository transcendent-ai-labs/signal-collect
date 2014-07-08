/*
 *  @author Philip Stutz
 *  @author Thomas Keller
 *  @author Mihaela Verman
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

package com.signalcollect.node

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import com.signalcollect.interfaces.Request
import com.signalcollect.interfaces.WorkerActor
import com.signalcollect.interfaces.NodeActor
import com.signalcollect.interfaces.MessageBusFactory
import com.signalcollect.interfaces.MessageBus
import scala.reflect.ClassTag
import scala.concurrent.duration.DurationInt
import com.signalcollect.interfaces.ActorRestartLogging
import com.signalcollect.interfaces.Heartbeat
import com.signalcollect.interfaces.MapperFactory
import com.signalcollect.interfaces.MessageBus
import com.signalcollect.interfaces.MessageBusFactory
import com.signalcollect.interfaces.Node
import com.signalcollect.interfaces.NodeActor
import com.signalcollect.interfaces.NodeReady
import com.signalcollect.interfaces.NodeStatus
import com.signalcollect.interfaces.Request
import com.signalcollect.interfaces.SentMessagesStats
import com.signalcollect.interfaces.WorkerActor
import com.signalcollect.interfaces.WorkerApi
import com.signalcollect.util.AkkaRemoteAddress
import akka.actor.ActorLogging
import com.signalcollect.interfaces.ActorRestartLogging
import com.signalcollect.interfaces.VertexToWorkerMapper
import com.signalcollect.interfaces.MapperFactory
import com.signalcollect.interfaces.NodeReady
import akka.util.Timeout
import scala.concurrent.Await
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.actorRef2Scala
import akka.japi.Creator
import com.signalcollect.interfaces.WorkerStatus

/**
 * Incrementor function needs to be defined in its own class to prevent unnecessary
 * closure capture when serialized.
 */
case class IncrementorForNode(nodeId: Int) {
  def increment(messageBus: MessageBus[_, _]) = {
    messageBus.incrementMessagesSentToNode(nodeId)
  }
}

/**
 * Class that controls a node on which Signal/Collect workers run.
 */
class DefaultNodeActor(
  val actorNamePrefix: String,
  val nodeId: Int,
  val numberOfNodes: Int,
  val nodeProvisionerAddress: Option[String] // Specify if the worker should report when it is ready.
  ) extends NodeActor
  with ActorLogging
  with ActorRestartLogging {

  // To keep track of sent messages before the message bus is initialized.
  var bootstrapMessagesSentToCoordinator = 0

  var receivedMessagesCounter = 0

  // To keep track of the workers this node is responsible for.
  var workers: List[ActorRef] = List[ActorRef]()
  var workerStatus: Array[WorkerStatus] = _
  var workerStatusAlreadyForwardedToCoordinator: Array[Boolean] = _

  var numberOfIdleWorkers = 0
  var isWorkerIdle: Array[Boolean] = _
  var numberOfWorkersOnNode = 0

  def initializeIdleDetection {
    receivedMessagesCounter -= 1
    workerStatus = new Array[WorkerStatus](numberOfWorkersOnNode)
    isWorkerIdle = new Array[Boolean](numberOfWorkersOnNode)
    workerStatusAlreadyForwardedToCoordinator = new Array[Boolean](numberOfWorkersOnNode)
  }
  
  def receive = {
    case Heartbeat(maySignal) =>
      sendStatusToCoordinator
    case w: WorkerStatus =>
      receivedMessagesCounter += 1
      val arrayIndex = w.workerId % numberOfWorkersOnNode
      if (isWorkerIdle(arrayIndex)) {
        if (!w.isIdle) {
          numberOfIdleWorkers -= 1
        }
      } else {
        if (w.isIdle) {
          numberOfIdleWorkers += 1
        }
      }
      workerStatus(arrayIndex) = w
      isWorkerIdle(arrayIndex) = w.isIdle
      workerStatusAlreadyForwardedToCoordinator(arrayIndex) = false
      if (numberOfIdleWorkers == numberOfWorkersOnNode) {
        var i = 0
        while (i < numberOfWorkersOnNode) {
          if (!workerStatusAlreadyForwardedToCoordinator(i)) {
            val status = workerStatus(i)
            if (status != null) {
              messageBus.sendToCoordinator(status)
              workerStatusAlreadyForwardedToCoordinator(i) = true
            }
          }
          i += 1
        }
        sendStatusToCoordinator // After the worker messages, so the counts for sending them is included.
      }
    case Request(command, reply, incrementor) =>
      receivedMessagesCounter += 1
      val result = command.asInstanceOf[Node => Any](this)
      if (reply) {
        if (result == null) { // Netty does not like null messages: org.jboss.netty.channel.socket.nio.NioWorker - WARNING: Unexpected exception in the selector loop. - java.lang.NullPointerException
          if (isInitialized) {
            // MessageBus will take care of counting the replies.
            messageBus.sendToActor(sender, None)
          } else {
            // Bootstrap answers, not counted yet.
            sender ! None
          }
        } else {
          if (isInitialized) {
            // MessageBus will take care of counting the replies.
            messageBus.sendToActor(sender, result)
          } else {
            // Bootstrap answers, not counted yet.
            sender ! result
          }
        }
      }
    case other =>
      println("Received unexpected message from " + sender + ": " + other)
  }

  var messageBus: MessageBus[_, _] = _

  var nodeProvisioner: ActorRef = _

  def initializeMessageBus(numberOfWorkers: Int, numberOfNodes: Int, messageBusFactory: MessageBusFactory, mapperFactory: MapperFactory) {
    receivedMessagesCounter -= 1 // Node messages are not counted.
    messageBus = messageBusFactory.createInstance(
      context.system, numberOfWorkers, numberOfNodes, mapperFactory.createInstance(numberOfNodes, numberOfWorkers / numberOfNodes), IncrementorForNode(nodeId).increment _)
  }

  protected var lastStatusUpdate = System.currentTimeMillis

  protected def getNodeStatus: NodeStatus = {
    NodeStatus(
      nodeId = nodeId,
      messagesSent = SentMessagesStats(
        messageBus.messagesSentToWorkers,
        messageBus.messagesSentToNodes,
        messageBus.messagesSentToCoordinator + bootstrapMessagesSentToCoordinator + 1, // +1 to account for the status message itself.
        messageBus.messagesSentToOthers),
      messagesReceived = receivedMessagesCounter)
  }

  protected def sendStatusToCoordinator {
    if (isInitialized) {
      val status = getNodeStatus
      messageBus.sendToCoordinator(status)
    }
  }

  def isInitialized = messageBus != null && messageBus.isInitialized

  def createWorker(workerId: Int, creator: () => WorkerActor[_, _]): String = {
    receivedMessagesCounter -= 1 // Node messages are not counted.
    numberOfWorkersOnNode += 1
    val workerName = "Worker" + workerId
    val worker = context.system.actorOf(
      Props(creator()).withDispatcher("akka.io.pinned-dispatcher"),
      name = actorNamePrefix + workerName)
    workers = worker :: workers
    AkkaRemoteAddress.get(worker, context.system)
  }

  def numberOfCores = {
    receivedMessagesCounter -= 1 // Node messages are not counted.
    Runtime.getRuntime.availableProcessors
  }

  override def preStart = {
    if (nodeProvisionerAddress.isDefined) {
      println(s"Registering with node provisioner @ ${nodeProvisionerAddress.get}")
      val selection = context.actorSelection(nodeProvisionerAddress.get)
      implicit val timeout = Timeout(30.seconds)
      val nodeProvisioner = Await.result(selection.resolveOne, 30.seconds)
      nodeProvisioner ! NodeReady(nodeId)
    }
  }

  def shutdown = {
    receivedMessagesCounter -= 1 // Node messages are not counted.
    context.system.shutdown
  }

  def registerWorker(workerId: Int, worker: ActorRef) {
    receivedMessagesCounter -= 1 // Bootstrapping messages are not counted.
    messageBus.registerWorker(workerId, worker)
  }

  def registerNode(nodeId: Int, node: ActorRef) {
    receivedMessagesCounter -= 1 // Bootstrapping messages are not counted.
    messageBus.registerNode(nodeId, node)
  }

  def registerCoordinator(coordinator: ActorRef) {
    receivedMessagesCounter -= 1 // Bootstrapping messages are not counted.
    messageBus.registerCoordinator(coordinator)
  }

}
