/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.yarn

import java.nio.ByteBuffer

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.DataOutputBuffer
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.{YarnClient, YarnClientApplication}
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.util.Records

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.deploy.SparkHadoopUtil

// HERE
import org.apache.hadoop.yarn.api.protocolrecords.ReservationSubmissionRequest
import org.apache.hadoop.yarn.api.protocolrecords.ReservationSubmissionResponse
import scala.collection.JavaConversions._

/**
 * Version of [[org.apache.spark.deploy.yarn.ClientBase]] tailored to YARN's stable API.
 */
private[spark] class Client(
    val args: ClientArguments,
    val hadoopConf: Configuration,
    val sparkConf: SparkConf)
  extends ClientBase with Logging {

  def this(clientArgs: ClientArguments, spConf: SparkConf) =
    this(clientArgs, SparkHadoopUtil.get.newConfiguration(spConf), spConf)

  def this(clientArgs: ClientArguments) = this(clientArgs, new SparkConf())

  val yarnClient = YarnClient.createYarnClient
  val yarnConf = new YarnConfiguration(hadoopConf)

  def stop(): Unit = yarnClient.stop()

  /* ------------------------------------------------------------------------------------- *
   | The following methods have much in common in the stable and alpha versions of Client, |
   | but cannot be implemented in the parent trait due to subtle API differences across    |
   | hadoop versions.                                                                      |
   * ------------------------------------------------------------------------------------- */

  /**
   * Submit an application running our ApplicationMaster to the ResourceManager.
   *
   * The stable Yarn API provides a convenience method (YarnClient#createApplication) for
   * creating applications and setting up the application submission context. This was not
   * available in the alpha API.
   */
  override def submitApplication(): ApplicationId = {
    yarnClient.init(yarnConf)
    yarnClient.start()

    logInfo("Requesting a new application from cluster with %d NodeManagers"
      .format(yarnClient.getYarnClusterMetrics.getNumNodeManagers))

    // Get a new application from our RM
    val newApp = yarnClient.createApplication()
    val newAppResponse = newApp.getNewApplicationResponse()
    val appId = newAppResponse.getApplicationId()

    // Verify whether the cluster has enough resources for our AM
    verifyClusterResources(newAppResponse)

    // Set up the appropriate contexts to launch our AM
    var containerContext: ContainerLaunchContext = null

    var appContext: ApplicationSubmissionContext = null

    // Muhuan HERE
    if (args.rsrvInUse == 1) {
      // does not reserve amContainer
      val executorAndAMRequest = ReservationRequest.newInstance(
        //Resource.newInstance(args.executorMemory * args.numExecutors + args.amMemory, 
        //  args.executorCores * args.numExecutors + 1,
        //  args.numAccs),
        //1,
        //1,
        //args.rsrvDuration,
        //args.rsrvSpeedup,
        //args.rsrvAccPercentage)
        Resource.newInstance(args.executorMemory, 
          args.executorCores,
          0),
        args.numExecutors,
        args.numExecutors,
        args.rsrvDuration,
        args.rsrvSpeedup,
        args.rsrvAccPercentage)
      val rsrvResources = new java.util.ArrayList[ReservationRequest]()
      rsrvResources.add(executorAndAMRequest)
      val rsrvRequests = ReservationRequests.newInstance(
        rsrvResources,
        ReservationRequestInterpreter.R_ALL)
      val rsrvDef = ReservationDefinition.newInstance(
        args.rsrvStartTime,
        args.rsrvDeadline, 
        rsrvRequests,
        "spark-reservation")
      val rsrvSubmissionRequest = 
        ReservationSubmissionRequest.newInstance(rsrvDef, args.rsrvQueue)
      val rsrvSubmissionResponse= yarnClient.submitReservation(
        rsrvSubmissionRequest)
      val rid = rsrvSubmissionResponse.getReservationId
      if (rid == null) {
        logInfo(s"Application ${appId.getId} reservation did not succeed")
        System.exit(1)
      }
      println("Reservation is successful.");

      // set in the appContext
      println("Allocate " + rsrvSubmissionResponse.getNumCpus() + " cpus, " +
        rsrvSubmissionResponse.getNumAccs() + " accs.");

      // wait til the starttime 
      var currentTime: Long = System.currentTimeMillis
      while(currentTime < args.rsrvStartTime) {
        println("job is ahead the specified starttime");
        Thread.sleep(1000)
        currentTime = System.currentTimeMillis
      }

      // wait til the queue has capacity
      var ready = false
      while(!ready) {
        var queueInfo = yarnClient.getQueueInfo(rid.toString)
        if (queueInfo != null) {
          if (queueInfo.getCapacity() > 0.0f) ready = true
        }
        if (!ready) {
          println("queue " + rid.toString + " not ready");
          Thread.sleep(1000)
        }
      }
      args.numExecutors = rsrvSubmissionResponse.getNumCpus()
      args.numAccs = rsrvSubmissionResponse.getNumAccs()
      // Set up the appropriate contexts to launch our AM
      containerContext = createContainerLaunchContext(newAppResponse)

      appContext = createApplicationSubmissionContext(newApp, containerContext)
      appContext.setReservationID(rid)
      appContext.setQueue(args.rsrvQueue)
    } else {
      // Set up the appropriate contexts to launch our AM
      containerContext = createContainerLaunchContext(newAppResponse)

      appContext = createApplicationSubmissionContext(newApp, containerContext)
    }


    // Finally, submit and monitor the application
    logInfo(s"Submitting application ${appId.getId} to ResourceManager")
    yarnClient.submitApplication(appContext)
    appId
  }

  /**
   * Set up the context for submitting our ApplicationMaster.
   * This uses the YarnClientApplication not available in the Yarn alpha API.
   */
  def createApplicationSubmissionContext(
      newApp: YarnClientApplication,
      containerContext: ContainerLaunchContext): ApplicationSubmissionContext = {
    val appContext = newApp.getApplicationSubmissionContext
    appContext.setApplicationName(args.appName)
    appContext.setQueue(args.amQueue)
    appContext.setAMContainerSpec(containerContext)
    appContext.setApplicationType("SPARK")
    val capability = Records.newRecord(classOf[Resource])
    capability.setMemory(args.amMemory + amMemoryOverhead)
    appContext.setResource(capability)
    appContext
  }

  /** Set up security tokens for launching our ApplicationMaster container. */
  override def setupSecurityToken(amContainer: ContainerLaunchContext): Unit = {
    val dob = new DataOutputBuffer
    credentials.writeTokenStorageToStream(dob)
    amContainer.setTokens(ByteBuffer.wrap(dob.getData))
  }

  /** Get the application report from the ResourceManager for an application we have submitted. */
  override def getApplicationReport(appId: ApplicationId): ApplicationReport =
    yarnClient.getApplicationReport(appId)

  /**
   * Return the security token used by this client to communicate with the ApplicationMaster.
   * If no security is enabled, the token returned by the report is null.
   */
  override def getClientToken(report: ApplicationReport): String =
    Option(report.getClientToAMToken).map(_.toString).getOrElse("")
}

object Client {
  def main(argStrings: Array[String]) {
    if (!sys.props.contains("SPARK_SUBMIT")) {
      println("WARNING: This client is deprecated and will be removed in a " +
        "future version of Spark. Use ./bin/spark-submit with \"--master yarn\"")
    }

    // Set an env variable indicating we are running in YARN mode.
    // Note that any env variable with the SPARK_ prefix gets propagated to all (remote) processes
    System.setProperty("SPARK_YARN_MODE", "true")
    val sparkConf = new SparkConf

    val args = new ClientArguments(argStrings, sparkConf)
    new Client(args, sparkConf).run()
  }
}
