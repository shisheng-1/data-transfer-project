package org.dataportabilityproject.webapp;

import com.google.api.client.auth.oauth2.AuthorizationCodeResponseUrl;
import com.google.common.base.Enums;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.dataportabilityproject.ServiceProviderRegistry;
import org.dataportabilityproject.shared.PortableDataType;
import org.dataportabilityproject.shared.auth.AuthData;
import org.dataportabilityproject.shared.auth.OnlineAuthDataGenerator;
import org.dataportabilityproject.webapp.job.JobManager;
import org.dataportabilityproject.webapp.job.PortabilityJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller for the list data types service. */
@RestController
public class OauthCallbackController {
  @Autowired
  private ServiceProviderRegistry serviceProviderRegistry;
  @Autowired
  private JobManager jobManager;

  /** Handle oauth callback requests. */
  @CrossOrigin(origins = "http://localhost:3000")
  @RequestMapping("/callback/**")
  public void handleOauthResponse(HttpServletRequest request,
      HttpServletResponse response) throws Exception {

    AuthorizationCodeResponseUrl authResponse = getResponseUrl(request);

    // check for user-denied error
    if (authResponse.getError() != null) {
      System.out.println("Authorization DENIED: " + authResponse.getError());
      response.sendRedirect("/error");
      return;
    }

    // TODO: Encrypt/decrypt state param with secure info
    String token = authResponse.getState();
    Preconditions.checkArgument(!Strings.isNullOrEmpty(token), "Token required");
    log("token: %s", token);

    // Valid job must be present
    PortabilityJob job = jobManager.findExistingJob(token);
    Preconditions.checkState(null != job, "existingJob not found for token: %s", token);
    log("job: %s", job);

    PortableDataType dataType = getDataType(job.dataType());
    log("dataType: %s", dataType);

    // TODO: Support import and export service
    // Hack! For now, if we don't have export auth data, assume it's for export.
    boolean isExport = (null == job.exportAuthData());
    System.out.println("\n\n*****\nOauth callback, job:\n\n" + job + "\n\n*****\n");

    // TODO: Determine service from job or from url path?
    String service = isExport ? job.exportService() : job.importService();
    Preconditions.checkState(!Strings.isNullOrEmpty(service), "service not found, service: %s isExport: %b, token: %s", service, isExport, token);
    log("service: %s, isExport: %b", service, isExport);

    // Obtain the ServiceProvider from the registry
    OnlineAuthDataGenerator generator = serviceProviderRegistry.getOnlineAuth(service, dataType);

    // Generate and store auth data
    AuthData authData = generator.generateAuthData(authResponse.getCode(), token);

    // Update the job
    PortabilityJob updatedJob = setAuthData(job, authData, isExport);
    jobManager.updateJob(updatedJob);

    if(isExport) {
      response.sendRedirect("http://localhost:3000/import");  // TODO: parameterize
    } else {
      response.sendRedirect("http://localhost:3000/copy");
    }

  }

  // Sets the service in the correct field of the PortabilityJob
  private PortabilityJob setAuthData(PortabilityJob job, AuthData authData, boolean isExportService) {
    PortabilityJob.Builder updatedJob = job.toBuilder();
    if (isExportService) {
      updatedJob.setExportAuthData(authData);
    } else {
      updatedJob.setImportAuthData(authData);
    }
    return updatedJob.build();
  }

  private static AuthorizationCodeResponseUrl getResponseUrl(HttpServletRequest request) {
    StringBuffer fullUrlBuf = request.getRequestURL();
    if (request.getQueryString() != null) {
      fullUrlBuf.append('?').append(request.getQueryString());
    }
    return new AuthorizationCodeResponseUrl(fullUrlBuf.toString());
  }

  /** Parse the data type .*/
  private static PortableDataType getDataType(String dataType) {
    Optional<PortableDataType> dataTypeOption = Enums.getIfPresent(PortableDataType.class, dataType);
    Preconditions.checkState(dataTypeOption.isPresent(), "Data type required");
    return dataTypeOption.get();
  }

  private void log (String fmt, Object... args) {
    System.out.println(String.format(fmt, args));
  }
}