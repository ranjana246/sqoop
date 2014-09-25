/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sqoop.client;

import org.apache.sqoop.client.request.SqoopResourceRequests;
import org.apache.sqoop.common.Direction;
import org.apache.sqoop.common.SqoopException;
import org.apache.sqoop.json.ConnectorBean;
import org.apache.sqoop.json.DriverConfigBean;
import org.apache.sqoop.json.ValidationResultBean;
import org.apache.sqoop.model.FormUtils;
import org.apache.sqoop.model.MLink;
import org.apache.sqoop.model.MConnector;
import org.apache.sqoop.model.MDriverConfig;
import org.apache.sqoop.model.MJob;
import org.apache.sqoop.model.MSubmission;
import org.apache.sqoop.validation.Status;
import org.apache.sqoop.validation.ValidationResult;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Sqoop client API.
 *
 * High level Sqoop client API to communicate with Sqoop server. Current
 * implementation is not thread safe.
 *
 * SqoopClient is keeping cache of objects that are unlikely to be changed
 * (Resources, Connector structures). Volatile structures (Links, Jobs)
 * are not cached.
 */
public class SqoopClient {

  /**
   * Underlying request object to fetch data from Sqoop server.
   */
  private SqoopResourceRequests resourceRequests;

  /**
   * True if user retrieved all connectors at once.
   */
  private boolean isAllConnectors;
  /**
   * All cached connectors.
   */
  private Map<Long, MConnector> connectors;
  /**
   * All cached bundles for all connectors.
   */
  private Map<Long, ResourceBundle> connectorConfigBundles;

  /**
   * Cached driverConfig.
   */
  private MDriverConfig driverConfig;
  /**
   * Cached driverConfig bundle.
   */
  private ResourceBundle driverConfigBundle;

  /**
   * Status flags used when updating the submission callback status
   */
  private enum SubmissionStatus {
    SUBMITTED,
    UPDATED,
    FINISHED
  }

  public SqoopClient(String serverUrl) {
    resourceRequests = new SqoopResourceRequests();
    setServerUrl(serverUrl);
  }

  /**
   * Set new server URL.
   *
   * Setting new URL will also clear all caches used by the client.
   *
   * @param serverUrl Server URL
   */
  public void setServerUrl(String serverUrl) {
    resourceRequests.setServerUrl(serverUrl);
    clearCache();
  }

  /**
   * Set arbitrary request object.
   *
   * @param requests SqoopRequests object
   */
  public void setSqoopRequests(SqoopResourceRequests requests) {
    this.resourceRequests = requests;
    clearCache();
  }

  /**
   * Clear internal cache.
   */
  public void clearCache() {
    connectorConfigBundles = new HashMap<Long, ResourceBundle>();
    driverConfigBundle = null;
    connectors = new HashMap<Long, MConnector>();
    driverConfig = null;
    isAllConnectors = false;
  }

  /**
   * Get connector with given id.
   *
   * @param cid Connector id.
   * @return
   */
  public MConnector getConnector(long cid) {
    if(connectors.containsKey(cid)) {
      return connectors.get(cid).clone(false);
    }

    retrieveConnector(cid);
    return connectors.get(cid).clone(false);
  }

  /**
   * Return connector with given name.
   *
   * @param connectorName Connector name
   * @return Connector model or NULL if the connector do not exists.
   */
  public MConnector getConnector(String connectorName) {
    // Firstly try if we have this connector already in cache
    MConnector connector = getConnectorFromCache(connectorName);
    if(connector != null) return connector;

    // If the connector wasn't in cache and we have all connectors,
    // it simply do not exists.
    if(isAllConnectors) return null;

    // Retrieve all connectors from server
    getConnectors();
    return getConnectorFromCache(connectorName);
  }

  /**
   * Iterate over cached connectors and return connector of given name.
   * This method will not contact server in case that the connector is
   * not found in the cache.
   *
   * @param connectorName Connector name
   * @return
   */
  private MConnector getConnectorFromCache(String connectorName) {
    for(MConnector connector : connectors.values()) {
      if(connector.getUniqueName().equals(connectorName)) {
        return connector;
      }
    }

    return null;
  }

  /**
   * Retrieve connector structure from server and cache it.
   *
   * @param cid Connector id
   */
  private void retrieveConnector(long cid) {
    ConnectorBean request = resourceRequests.readConnector(cid);
    connectors.put(cid, request.getConnectors().get(0));
    connectorConfigBundles.put(cid, request.getResourceBundles().get(cid));
  }

  /**
   * Get list of all connectors.
   *
   * @return
   */
  public Collection<MConnector> getConnectors() {
    if(isAllConnectors) {
      return connectors.values();
    }

    ConnectorBean bean = resourceRequests.readConnector(null);
    isAllConnectors = true;
    for(MConnector connector : bean.getConnectors()) {
      connectors.put(connector.getPersistenceId(), connector);
    }
    connectorConfigBundles = bean.getResourceBundles();

    return connectors.values();
  }

  /**
   * Get resource bundle for given connector.
   *
   * @param connectorId Connector id.
   * @return
   */
  public ResourceBundle getConnectorConfigResourceBundle(long connectorId) {
    if(connectorConfigBundles.containsKey(connectorId)) {
      return connectorConfigBundles.get(connectorId);
    }

    retrieveConnector(connectorId);
    return connectorConfigBundles.get(connectorId);
  }

  /**
   * Return driver config.
   *
   * @return
   */
  public MDriverConfig getDriverConfig() {
    if(driverConfig != null) {
      return driverConfig.clone(false);
    }
    retrieveAndCacheDriverConfig();
    return driverConfig.clone(false);

  }

  /**
   * Retrieve driverConfig and cache it.
   */
  private void retrieveAndCacheDriverConfig() {
    DriverConfigBean driverConfigBean =  resourceRequests.readDriverConfig();
    driverConfig = driverConfigBean.getDriverConfig();
    driverConfigBundle = driverConfigBean.getResourceBundle();
  }

  /**
   * Return driverConfig bundle.
   *
   * @return
   */
  public ResourceBundle getDriverConfigBundle() {
    if(driverConfigBundle != null) {
      return driverConfigBundle;
    }
    retrieveAndCacheDriverConfig();
    return driverConfigBundle;
  }

  /**
   * Create new link object for given connector id
   *
   * @param connectorId Connector id
   * @return
   */
  public MLink createLink(long connectorId) {
    return new MLink(
      connectorId,
      getConnector(connectorId).getConnectionForms(),
      getDriverConfig().getConnectionForms()
    );
  }

  /**
   * Create new link object for given connector name
   *
   * @param connectorName Connector name
   * @return
   */
  public MLink createLink(String connectorName) {
    MConnector connector = getConnector(connectorName);
    if(connector == null) {
      throw new SqoopException(ClientError.CLIENT_0003, connectorName);
    }

    return createLink(connector.getPersistenceId());
  }

  /**
   * Retrieve link for given id.
   *
   * @param linkId Link id
   * @return
   */
  public MLink getLink(long linkId) {
    return resourceRequests.readLink(linkId).getLinks().get(0);
  }

  /**
   * Retrieve list of all links.
   *
   * @return
   */
  public List<MLink> getLinks() {
    return resourceRequests.readLink(null).getLinks();
  }

  /**
   * Create the link and save to the repository
   *
   * @param link link that should be created
   * @return
   */
  public Status saveLink(MLink link) {
    return applyLinkValidations(resourceRequests.saveLink(link), link);
  }

  /**
   * Update link on the server.
   *
   * @param link link that should be updated
   * @return
   */
  public Status updateLink(MLink link) {
    return applyLinkValidations(resourceRequests.updateLink(link), link);
  }

  /**
   * Enable/disable link with given id
   *
   * @param linkId link id
   * @param enabled Enable or disable
   */
  public void enableLink(long linkId, boolean enabled) {
    resourceRequests.enableLink(linkId, enabled);
  }

  /**
   * Delete link with given id.
   *
   * @param linkId link id
   */
  public void deleteLink(long linkId) {
    resourceRequests.deleteLink(linkId);
  }

  /**
   * Create new job the for given links.
   *
   * @param fromLinkId From link id
   * @param toLinkId To link id
   * @return
   */
  public MJob createJob(long fromLinkId, long toLinkId) {
    MLink fromLink = getLink(fromLinkId);
    MLink toLink = getLink(toLinkId);

    return new MJob(
      fromLink.getConnectorId(),
      toLink.getConnectorId(),
      fromLink.getPersistenceId(),
      toLink.getPersistenceId(),
      getConnector(fromLink.getConnectorId()).getJobForms(Direction.FROM),
      getConnector(toLink.getConnectorId()).getJobForms(Direction.TO),
      getDriverConfig().getJobForms()
    );
  }

  /**
   * Retrieve job for given id.
   *
   * @param jobId Job id
   * @return
   */
  public MJob getJob(long jobId) {
    return resourceRequests.readJob(jobId).getJobs().get(0);
  }

  /**
   * Retrieve list of all jobs.
   *
   * @return
   */
  public List<MJob> getJobs() {
    return resourceRequests.readJob(null).getJobs();
  }

  /**
   * Create job on server and save to the repository
   *
   * @param job Job that should be created
   * @return
   */
  public Status saveJob(MJob job) {
    return applyJobValidations(resourceRequests.saveJob(job), job);
  }

  /**
   * Update job on server.
   * @param job Job that should be updated
   * @return
   */
  public Status updateJob(MJob job) {
    return applyJobValidations(resourceRequests.updateJob(job), job);
  }

  /**
   * Enable/disable job with given id
   *
   * @param jid Job that is going to be enabled/disabled
   * @param enabled Enable or disable
   */
  public void enableJob(long jid, boolean enabled) {
    resourceRequests.enableJob(jid, enabled);
  }

  /**
   * Delete job with given id.
   *
   * @param jobId Job id
   */
  public void deleteJob(long jobId) {
    resourceRequests.deleteJob(jobId);
  }

  /**
   * Start job with given id.
   *
   * @param jobId Job id
   * @return
   */
  public MSubmission startSubmission(long jobId) {
    return resourceRequests.createSubmission(jobId).getSubmissions().get(0);
  }

  /**
   * Method used for synchronous job submission.
   * Pass null to callback parameter if submission status is not required and after completion
   * job execution returns MSubmission which contains final status of submission.
   * @param jobId - Job ID
   * @param callback - User may set null if submission status is not required, else callback methods invoked
   * @param pollTime - Server poll time
   * @return MSubmission - Final status of job submission
   * @throws InterruptedException
   */
  public MSubmission startSubmission(long jobId, SubmissionCallback callback, long pollTime)
      throws InterruptedException {
    if(pollTime <= 0) {
      throw new SqoopException(ClientError.CLIENT_0002);
    }
    boolean first = true;
    MSubmission submission = resourceRequests.createSubmission(jobId).getSubmissions().get(0);
    while(submission.getStatus().isRunning()) {
      if(first) {
        submissionCallback(callback, submission, SubmissionStatus.SUBMITTED);
        first = false;
      } else {
        submissionCallback(callback, submission, SubmissionStatus.UPDATED);
      }
      Thread.sleep(pollTime);
      submission = getSubmissionStatus(jobId);
    }
    submissionCallback(callback, submission, SubmissionStatus.FINISHED);
    return submission;
  }

  /**
   * Invokes the callback's methods with MSubmission object
   * based on SubmissionStatus. If callback is null, no operation performed.
   * @param callback
   * @param submission
   * @param status
   */
  private void submissionCallback(SubmissionCallback callback, MSubmission submission,
      SubmissionStatus status) {
    if (callback == null) {
      return;
    }
    switch (status) {
    case SUBMITTED:
      callback.submitted(submission);
      break;
    case UPDATED:
      callback.updated(submission);
      break;
    case FINISHED:
      callback.finished(submission);
    }
  }

  /**
   * Stop job with given id.
   *
   * @param jid Job id
   * @return
   */
  public MSubmission stopSubmission(long jid) {
    return resourceRequests.deleteSubmission(jid).getSubmissions().get(0);
  }

  /**
   * Get status for given job id.
   *
   * @param jid Job id
   * @return
   */
  public MSubmission getSubmissionStatus(long jid) {
    return resourceRequests.readSubmission(jid).getSubmissions().get(0);
  }

  /**
   * Retrieve list of all submissions.
   *
   * @return
   */
  public List<MSubmission> getSubmissions() {
    return resourceRequests.readHistory(null).getSubmissions();
  }

  /**
   * Retrieve list of submissions for given jobId.
   *
   * @param jobId Job id
   * @return
   */
  public List<MSubmission> getSubmissionsForJob(long jobId) {
    return resourceRequests.readHistory(jobId).getSubmissions();
  }

  private Status applyLinkValidations(ValidationResultBean bean, MLink link) {
    ValidationResult connector = bean.getValidationResults()[0];
    ValidationResult driverConfig = bean.getValidationResults()[1];

    // Apply validation results
    FormUtils.applyValidation(link.getConnectorPart().getForms(), connector);
    FormUtils.applyValidation(link.getFrameworkPart().getForms(), driverConfig);

    Long id = bean.getId();
    if(id != null) {
      link.setPersistenceId(id);
    }

    return Status.getWorstStatus(connector.getStatus(), driverConfig.getStatus());
  }

  private Status applyJobValidations(ValidationResultBean bean, MJob job) {
    ValidationResult fromConnector = bean.getValidationResults()[0];
    ValidationResult toConnector = bean.getValidationResults()[1];
    ValidationResult driverConfig = bean.getValidationResults()[2];

    // Apply validation results
    // @TODO(Abe): From/To validation.
    FormUtils.applyValidation(
        job.getConnectorPart(Direction.FROM).getForms(),
        fromConnector);
    FormUtils.applyValidation(job.getFrameworkPart().getForms(), driverConfig);
    FormUtils.applyValidation(
        job.getConnectorPart(Direction.TO).getForms(),
        toConnector);

    Long id = bean.getId();
    if(id != null) {
      job.setPersistenceId(id);
    }

    return Status.getWorstStatus(fromConnector.getStatus(), driverConfig.getStatus(), toConnector.getStatus());
  }
}