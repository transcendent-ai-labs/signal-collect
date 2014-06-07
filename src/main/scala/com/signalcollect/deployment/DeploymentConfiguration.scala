/*
 *  @author Tobias Bachmann
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

package com.signalcollect.configuration

import com.signalcollect.interfaces.MessageBusFactory
import com.signalcollect.interfaces.StorageFactory
import com.signalcollect.interfaces.WorkerFactory
import com.signalcollect.nodeprovisioning.NodeProvisioner
import com.signalcollect.nodeprovisioning.local.LocalNodeProvisioner
import com.signalcollect.factory.messagebus.AkkaMessageBusFactory
import akka.event.Logging.LogLevel
import akka.event.Logging
import com.signalcollect.factory.worker.DefaultAkkaWorker
import com.signalcollect.interfaces.SchedulerFactory
import com.signalcollect.factory.scheduler.Throughput
import com.signalcollect.interfaces.MapperFactory
import com.signalcollect.factory.mapper.DefaultMapperFactory
import com.signalcollect.storage.VertexMapStorage
import com.signalcollect.factory.storage.MemoryEfficientStorage
import akka.actor.ActorRef
import akka.actor.ActorSystem

/**
 * All the deployment parameters 
 */
case class DeploymentConfiguration(
  algorithm: String, //class name of a DeployableAlgorithm
  algorithmParameters: Map[String,String],
  memoryPerNode: Int = 512,
  numberOfNodes: Int = 1,
  copyFiles: List[String] = Nil, // list of paths to files
  clustertype: String = "yarn")