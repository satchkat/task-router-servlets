package com.twilio.taskrouter.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.base.ResourceSet;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.taskrouter.v1.Workspace;
import com.twilio.rest.taskrouter.v1.WorkspaceCreator;
import com.twilio.rest.taskrouter.v1.WorkspaceDeleter;
import com.twilio.rest.taskrouter.v1.WorkspaceFetcher;
import com.twilio.rest.taskrouter.v1.WorkspaceReader;
import com.twilio.rest.taskrouter.v1.workspace.Activity;
import com.twilio.rest.taskrouter.v1.workspace.ActivityReader;
import com.twilio.rest.taskrouter.v1.workspace.TaskQueue;
import com.twilio.rest.taskrouter.v1.workspace.TaskQueueCreator;
import com.twilio.rest.taskrouter.v1.workspace.TaskQueueReader;
import com.twilio.rest.taskrouter.v1.workspace.Worker;
import com.twilio.rest.taskrouter.v1.workspace.WorkerCreator;
import com.twilio.rest.taskrouter.v1.workspace.WorkerReader;
import com.twilio.rest.taskrouter.v1.workspace.WorkerUpdater;
import com.twilio.rest.taskrouter.v1.workspace.Workflow;
import com.twilio.rest.taskrouter.v1.workspace.WorkflowCreator;
import com.twilio.rest.taskrouter.v1.workspace.WorkflowReader;
import com.twilio.taskrouter.domain.error.TaskRouterException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

public class WorkspaceFacade {

  private final TwilioRestClient twilioRestClient;

  private final Workspace workspace;

  private Activity idleActivity;

  private Map<String, Worker> phoneToWorker;

  public WorkspaceFacade(TwilioRestClient twilioRestClient, Workspace workspace) {
    this.twilioRestClient = twilioRestClient;
    this.workspace = workspace;
  }

  public static WorkspaceFacade create(TwilioRestClient twilioRestClient, Map<String, String> params) {
    String workspaceName = params.get("FriendlyName");
    String eventCallbackUrl = params.get("EventCallbackUrl");

    ResourceSet<Workspace> execute = new WorkspaceReader().byFriendlyName(workspaceName).execute(twilioRestClient);
    StreamSupport.stream(execute.spliterator(), false)
      .findFirst()
      .ifPresent(workspace -> new WorkspaceDeleter(workspace.getSid()).execute(twilioRestClient));

    Workspace workspace = new WorkspaceCreator(workspaceName)
      .setEventCallbackUrl(eventCallbackUrl)
      .execute(twilioRestClient);

    return new WorkspaceFacade(twilioRestClient, workspace);
  }

  public static Optional<WorkspaceFacade> findBySid(String workspaceSid, TwilioRestClient twilioRestClient) {
    Workspace workspace = new WorkspaceFetcher(workspaceSid).execute(twilioRestClient);
    return Optional.of(new WorkspaceFacade(twilioRestClient, workspace));
  }

  public String getFriendlyName() {
    return workspace.getFriendlyName();
  }

  public String getSid() {
    return workspace.getSid();
  }

  public Worker addWorker(Map<String, String> workerParams) {
    return new WorkerCreator(workspace.getSid(), workerParams.get("FriendlyName"))
      .setActivitySid(workerParams.get("ActivitySid"))
      .setAttributes(workerParams.get("Attributes"))
      .execute(twilioRestClient);
  }

  public void addTaskQueue(Map<String, String> taskQueueParams) {
    new TaskQueueCreator(this.workspace.getSid(),
      taskQueueParams.get("FriendlyName"),
      taskQueueParams.get("ReservationActivitySid"),
      taskQueueParams.get("AssignmentActivitySid"))
      .execute(twilioRestClient);
  }

  public Workflow addWorkflow(Map<String, String> workflowParams) {
    return new WorkflowCreator(workspace.getSid(),
      workflowParams.get("FriendlyName"),
      workflowParams.get("Configuration"))
      .setAssignmentCallbackUrl(workflowParams.get("AssignmentCallbackUrl"))
      .setFallbackAssignmentCallbackUrl(workflowParams.get("FallbackAssignmentCallbackUrl"))
      .setTaskReservationTimeout(Integer.valueOf(workflowParams.get("TaskReservationTimeout")))
      .execute(twilioRestClient);
  }

  public Optional<Activity> findActivityByName(String activityName) {
    return StreamSupport.stream(new ActivityReader(this.workspace.getSid())
      .byFriendlyName(activityName)
      .execute(twilioRestClient).spliterator(), false
    ).findFirst();
  }

  public Optional<TaskQueue> findTaskQueueByName(String queueName) {
    return StreamSupport.stream(new TaskQueueReader(this.workspace.getSid())
      .byFriendlyName(queueName)
      .execute(twilioRestClient).spliterator(), false
    ).findFirst();
  }

  public Optional<Workflow> findWorkflowByName(String workflowName) {
    return StreamSupport.stream(new WorkflowReader(this.workspace.getSid())
      .byFriendlyName(workflowName)
      .execute(twilioRestClient).spliterator(), false
    ).findFirst();
  }

  public Optional<Worker> findWorkerByPhone(String workerPhone) {
    return Optional.ofNullable(getPhoneToWorker().get(workerPhone));
  }

  public Map<String, Worker> getPhoneToWorker() {
    if (phoneToWorker == null) {
      phoneToWorker = new HashMap<>();
      StreamSupport.stream(
        new WorkerReader(this.workspace.getSid()).execute(twilioRestClient).spliterator(), false
      ).forEach(worker -> {
        try {
          HashMap<String, Object> attributes = new ObjectMapper().readValue(worker.getAttributes(), HashMap.class);
          phoneToWorker.put(attributes.get("contact_uri").toString(), worker);
        } catch (IOException e) {
          throw new TaskRouterException(
            String.format("'%s' has a malformed json attributes", worker.getFriendlyName()));
        }
      });
    }
    return phoneToWorker;
  }

  public Activity getIdleActivity() {
    if (idleActivity == null) {
      idleActivity = findActivityByName("Idle").get();
    }
    return idleActivity;
  }

  public void updateWorkerStatus(Worker worker, String activityFriendlyName) {
    Activity activity = findActivityByName(activityFriendlyName).orElseThrow(() ->
      new TaskRouterException(
        String.format("The activity '%s' doesn't exist in the workspace", activityFriendlyName)
      )
    );

    new WorkerUpdater(workspace.getSid(), worker.getSid())
      .setActivitySid(activity.getSid())
      .execute(twilioRestClient);
  }
}
