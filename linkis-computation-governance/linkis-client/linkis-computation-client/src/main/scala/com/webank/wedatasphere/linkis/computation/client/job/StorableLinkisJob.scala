package com.webank.wedatasphere.linkis.computation.client.job

import com.webank.wedatasphere.linkis.computation.client.LinkisJobMetrics
import com.webank.wedatasphere.linkis.computation.client.operator.StorableOperator
import com.webank.wedatasphere.linkis.governance.common.entity.task.RequestPersistTask
import com.webank.wedatasphere.linkis.ujes.client.UJESClient
import com.webank.wedatasphere.linkis.ujes.client.request.{JobSubmitAction, OpenLogAction}
import com.webank.wedatasphere.linkis.ujes.client.response.{JobInfoResult, JobSubmitResult}

/**
  * Created by enjoyyin on 2021/6/1.
  */
trait StorableLinkisJob extends AbstractLinkisJob {

  private var completedJobInfoResult: JobInfoResult = _

  protected def wrapperId[T](op: => T): T = super.wrapperObj(getId, "taskId must be exists.")(op)

  protected val ujesClient: UJESClient

  protected def getJobSubmitResult: JobSubmitResult

  override protected def wrapperObj[T](obj: Object, errorMsg: String)(op: => T): T = wrapperId {
    super.wrapperObj(obj, errorMsg)(op)
  }

  protected def getJobInfoResult: JobInfoResult = {
    if(completedJobInfoResult != null) return completedJobInfoResult
    val startTime = System.currentTimeMillis
    val jobInfoResult = wrapperId(ujesClient.getJobInfo(getJobSubmitResult))
    getJobMetrics.addClientGetJobInfoTime(System.currentTimeMillis - startTime)
    if(jobInfoResult.isCompleted) {
      getJobMetrics.setClientFinishedTime(System.currentTimeMillis)
      info(s"Job-$getId is completed with status " + completedJobInfoResult.getJobStatus)
      completedJobInfoResult = jobInfoResult
      getJobListeners.foreach(_.onJobFinished(this))
    } else if(jobInfoResult.isRunning)
      getJobListeners.foreach(_.onJobRunning(this))
    jobInfoResult
  }

  def getJobInfo: RequestPersistTask = getJobInfoResult.getRequestPersistTask

  def getAllLogs: Array[String] = wrapperId {
    val jobInfo = getJobInfo
    val action = OpenLogAction.newBuilder().setLogPath(jobInfo.getLogPath)
      .setProxyUser(jobInfo.getUmUser).build()
    ujesClient.openLog(action).getLog
  }

  override def doKill(): Unit = wrapperId(ujesClient.kill(getJobSubmitResult))

  override def isCompleted: Boolean = getJobInfoResult.isCompleted

  override def isSucceed: Boolean = getJobInfoResult.isSucceed
}

abstract class StorableSubmittableLinkisJob(override protected val ujesClient: UJESClient,
                                            jobSubmitAction: JobSubmitAction)
  extends StorableLinkisJob with AbstractSubmittableLinkisJob {

  private var taskId: String = _
  private var jobSubmitResult: JobSubmitResult = _

  override def getId: String = taskId

  override protected def getJobSubmitResult: JobSubmitResult = jobSubmitResult

  protected override def wrapperId[T](op: => T): T = super.wrapperObj(taskId, "Please submit job first.")(op)

  override protected def doSubmit(): Unit = {
    info("Ready to submit job: " + jobSubmitAction.getRequestPayload)
    jobSubmitResult = ujesClient.submit(jobSubmitAction)
    taskId = jobSubmitResult.taskID
    addOperatorAction {
      case operator: StorableOperator[_] => operator.setJobSubmitResult(jobSubmitResult).setUJESClient(ujesClient)
      case operator => operator
    }
    info("Job submitted with taskId: " + taskId)
  }

}

abstract class StorableExistingLinkisJob(protected override val ujesClient: UJESClient,
                                         execId: String,
                                         taskId: String, user: String) extends StorableLinkisJob {

  private val jobSubmitResult = new JobSubmitResult
  private val jobMetrics: LinkisJobMetrics = new LinkisJobMetrics(taskId)
  jobSubmitResult.setUser(user)
  jobSubmitResult.setTaskID(taskId)
  jobSubmitResult.setExecID(execId)
  jobMetrics.setClientSubmitTime(System.currentTimeMillis)

  override protected def getJobSubmitResult: JobSubmitResult = jobSubmitResult

  override def getId: String = taskId

  override def getJobMetrics: LinkisJobMetrics = jobMetrics
}