/*
 *  @author Philip Stutz
 *  
 *  Copyright 2011 University of Zurich
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

package com.signalcollect

import com.signalcollect.configuration._
import com.signalcollect.interfaces._
import com.signalcollect.nodeprovisioning.NodeProvisioner
import scala.reflect.ClassTag

/**
 *  A graph builder holds a configuration with parameters for building a graph,
 *  functions to modify this configuration and a build function to instantiate a graph
 *  with the defined configuration. This object represents a graph builder that is initialized with the default configuration.
 */
object GraphBuilder extends GraphBuilder[Any, Any](GraphConfiguration())

/**
 * Configurable builder for a Signal/Collect graph.
 *
 *  @author Philip Stutz
 */
class GraphBuilder[Id: ClassTag, Signal: ClassTag](protected val config: GraphConfiguration = GraphConfiguration()) extends Serializable {

  /**
   *  Creates a graph with the specified configuration.
   */
  def build: Graph[Id, Signal] = new DefaultGraph[Id, Signal](config)

  /**
   *  Configures if the console website on port 8080 is enabled.
   */
  def withConsole(newConsoleEnabled: Boolean) = newLocalBuilder(consoleEnabled = newConsoleEnabled)

  /**
   *  Configures if Akka message compression is enabled.
   */
  def withAkkaMessageCompression(newAkkaMessageCompression: Boolean) = newLocalBuilder(akkaMessageCompression = newAkkaMessageCompression)

  /**
   *  Configures the logging level.
   *
   *  @note Logging levels available:
   *    Debug = 0
   *    Config = 100
   *    Info = 200
   *    Warning = 300
   *    Severe = 400
   *
   *  @param newLoggingLevel The logging level used by the graph.
   */
  def withLoggingLevel(newLoggingLevel: Int) = newLocalBuilder(loggingLevel = newLoggingLevel)

  /**
   *  Configures the logger used by the graph.
   *
   *  @param logger The logger used by the graph.
   */
  def withLogger(logger: LogMessage => Unit) = newLocalBuilder(logger = logger)

  /**
   *  Configures the worker factory used by the graph to instantiate workers.
   *
   *  @param newWorkerFactory The worker factory used to instantiate workers.
   */
  def withWorkerFactory(newWorkerFactory: WorkerFactory) = newLocalBuilder(workerFactory = newWorkerFactory)

  /**
   *  Configures the message bus factory used by the graph to instantiate message buses.
   *
   *  @param newMessageBusFactory The message bus factory used to instantiate message buses.
   */
  def withMessageBusFactory(newMessageBusFactory: MessageBusFactory) = newLocalBuilder(messageBusFactory = newMessageBusFactory)

  /**
   *  Configures the storage factory used by the workers to instantiate vertex stores.
   *
   *  @param newStorageFactory The storage factory used to instantiate vertex stores.
   */
  def withStorageFactory(newStorageFactory: StorageFactory) = newLocalBuilder(storageFactory = newStorageFactory)

  /**
   *  Configures the status update interval (in milliseconds).
   */
  def withStatusUpdateInterval(newStatusUpdateInterval: Long) = newLocalBuilder(statusUpdateIntervalInMillis = newStatusUpdateInterval)

  /**
   *  Configures the Akka dispatcher for the worker actors.
   */
  def withAkkaDispatcher(newAkkaDispatcher: AkkaDispatcher) = newLocalBuilder(akkaDispatcher = newAkkaDispatcher)

  /**
   *  Configures the node provider.
   *
   *  @param newNodeProvider The node provider will acquire the resources for running a graph algorithm.
   */
  def withNodeProvisioner(newNodeProvisioner: NodeProvisioner) = newLocalBuilder(nodeProvisioner = newNodeProvisioner)

  /**
   *  Specifies how many messages can be sent and not received in the distributed system, before worker throttling kicks in.
   *  If throttling kicks in, the workers will not send any messages, until the number of sent but not received messages is below the threshold.
   *
   *  @param withThrottleInboxThresholdPerWorker The average number of messages that can be underway per worker (without triggering throttling).
   */  
  def withThrottleInboxThresholdPerWorker(newThrottleInboxThresholdPerWorker: Int) = newLocalBuilder(throttleInboxThresholdPerWorker = newThrottleInboxThresholdPerWorker)
 
  /**
   *  Specifies how many milliseconds the heartbeat message from the coordinator can be delayed, before worker throttling kicks in.
   *  If throttling kicks in, the worker will not send any messages, until the queue delay is reduced below the threshold.
   *
   *  @param withThrottleWorkerQueueThresholdInMilliseconds The maximum allowed delay of the coordinator heartbeat message (without triggering throttling).
   */  
  def withThrottleWorkerQueueThresholdInMilliseconds(newThrottleWorkerQueueThresholdInMilliseconds: Int) = newLocalBuilder(throttleWorkerQueueThresholdInMilliseconds = newThrottleWorkerQueueThresholdInMilliseconds)
  
  /**
   *  Internal function to create a new builder instance that has a configuration which defaults
   *  to parameters that are the same as the ones in this instance, unless explicitly set differently.
   */
  protected def newLocalBuilder(
    consoleEnabled: Boolean = config.consoleEnabled,
    loggingLevel: Int = config.loggingLevel,
    logger: LogMessage => Unit = config.logger,
    workerFactory: WorkerFactory = config.workerFactory,
    messageBusFactory: MessageBusFactory = config.messageBusFactory,
    storageFactory: StorageFactory = config.storageFactory,
    statusUpdateIntervalInMillis: Long = config.statusUpdateIntervalInMillis,
    akkaDispatcher: AkkaDispatcher = config.akkaDispatcher,
    akkaMessageCompression: Boolean = config.akkaMessageCompression,
    nodeProvisioner: NodeProvisioner = config.nodeProvisioner,
    throttleInboxThresholdPerWorker: Int = config.throttleInboxThresholdPerWorker,
    throttleWorkerQueueThresholdInMilliseconds: Int = config.throttleWorkerQueueThresholdInMilliseconds): GraphBuilder[Id, Signal] = {
    new GraphBuilder[Id, Signal](
      GraphConfiguration(
        consoleEnabled = consoleEnabled,
        loggingLevel = loggingLevel,
        logger = logger,
        workerFactory = workerFactory,
        messageBusFactory = messageBusFactory,
        storageFactory = storageFactory,
        statusUpdateIntervalInMillis = statusUpdateIntervalInMillis,
        akkaDispatcher = akkaDispatcher,
        akkaMessageCompression = akkaMessageCompression,
        nodeProvisioner = nodeProvisioner,
        throttleInboxThresholdPerWorker = throttleInboxThresholdPerWorker,
        throttleWorkerQueueThresholdInMilliseconds = throttleWorkerQueueThresholdInMilliseconds))
  }

}
