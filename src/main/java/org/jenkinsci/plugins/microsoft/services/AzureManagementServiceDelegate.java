/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.services;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.models.Deployment;
import com.microsoft.azure.management.resources.models.DeploymentMode;
import com.microsoft.azure.management.resources.models.DeploymentOperation;
import com.microsoft.azure.management.resources.models.DeploymentProperties;
import com.microsoft.azure.management.resources.models.ProvisioningState;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.exception.ServiceException;

import org.jenkinsci.plugins.microsoft.commands.IBaseCommandData;
import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;
import org.jenkinsci.plugins.microsoft.services.AzureManagementServiceDelegate;
import org.jenkinsci.plugins.microsoft.util.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.logging.Level;

import org.apache.commons.lang.StringUtils;

public class AzureManagementServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

    /**
     * Validates certificate configuration.
     *
     * @param subscriptionId
     * @param clientId
     * @param oauth2TokenEndpoint
     * @param clientSecret
     * @param serviceManagementURL
     * @return
     */
    public static String verifyConfiguration(
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL) {
        try {
            return verifyConfiguration(ServiceDelegateHelper.loadConfiguration(
                    subscriptionId,
                    clientId,
                    clientSecret,
                    oauth2TokenEndpoint,
                    serviceManagementURL));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating configuration", e);
            return "Failure: Exception occured while validating subscription configuration " + e;
        }
    }

    public static String verifyConfiguration(final Configuration config) {
        Callable<String> task = new Callable<String>() {

            @Override
            public String call() throws Exception {
                ServiceDelegateHelper.getStorageManagementClient(config).getStorageAccountsOperations().
                        checkNameAvailability("CI_SYSTEM");
                return Constants.OP_SUCCESS;
            }
        };

    	ExecutorService service = Executors.newSingleThreadExecutor();
        try {
        	Future<String> future = service.submit(task);
        	service.shutdown();
        	return future.get().toString();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.log(Level.SEVERE, "Error validating configuration", e);
            return "Failure: Exception occured while validating subscription configuration " + e;
        }finally {
        	service.shutdown();
        }
    }
    
    public static String deploy(final IARMTemplateServiceData azureServiceData)
            throws AzureCloudException {
        try {
            final ResourceManagementClient client = ServiceDelegateHelper.getResourceManagementClient(
                    ServiceDelegateHelper.load(azureServiceData.getAzureConnectionData()));

            final long ts = System.currentTimeMillis();

            final Deployment deployment = new Deployment();
            final DeploymentProperties properties = new DeploymentProperties();
            deployment.setProperties(properties);

            final InputStream embeddedTemplate;

            // check if a custom image id has been provided otherwise work with publisher and offer
            LOGGER.log(Level.INFO, "Use embedded deployment template {0}", azureServiceData.getEmbeddedTemplateName());
            embeddedTemplate
                    = AzureManagementServiceDelegate.class.getResourceAsStream(azureServiceData.getEmbeddedTemplateName());
            
            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode tmp = mapper.readTree(embeddedTemplate);

            azureServiceData.configureTemplate(tmp);
            
            // Deployment ....
            properties.setMode(DeploymentMode.INCREMENTAL);
            properties.setTemplate(tmp.toString());

            final String deploymentName = String.valueOf(ts);
            client.getDeploymentsOperations().createOrUpdate(azureServiceData.getResourceGroupName(), deploymentName, deployment);
            return deploymentName;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate: deployment: Unable to deploy", e);
            throw new AzureCloudException(e);
        }
    }
    
    public static void validateAndAddFieldValue(String type,
    		String fieldValue, 
    		String fieldName, 
    		String errorMessage, 
    		JsonNode tmp) 
    		throws AzureCloudException, IllegalAccessException{
        if (StringUtils.isNotBlank(fieldValue)) {
            // Add count variable for loop....
        	final ObjectMapper mapper = new ObjectMapper();
			final ObjectNode parameter = mapper.createObjectNode();
			parameter.put("type", type);
			if(type == "int") {
				parameter.put("defaultValue", Integer.parseInt(fieldValue));
			}else {
				parameter.put("defaultValue", fieldValue);
			}
            ObjectNode.class.cast(tmp.get("parameters")).replace(fieldName, parameter);
        } else if (StringUtils.isBlank(errorMessage)) {
            throw new AzureCloudException(errorMessage);
        }
    }
    
    public static boolean monitor(
    		ResourceManagementClient rmc,
    		String rcName,
    		String deploymentName,
    		IBaseCommandData baseCommandData) {
    	int completed = 0;
        do {
            try {
                Thread.sleep(30 * 1000);
            } catch (InterruptedException ex) {
                // ignore
            }

            List<DeploymentOperation> ops = null;
			try {
				//change to deployment name
				ops = rmc.getDeploymentOperationsOperations().
				        list(rcName, deploymentName, null).getOperations();
			} catch (IOException | ServiceException | URISyntaxException e) {
			    LOGGER.log(Level.INFO, "Failed getting deployment operations" + e.getMessage());
			    baseCommandData.logError("Failed getting deployment operations" + e.getMessage());
			    return false;
			}
			
            completed = ops.size();
            for (DeploymentOperation op : ops) {
                final String resource = op.getProperties().getTargetResource().getResourceName();
                final String type = op.getProperties().getTargetResource().getResourceType();
                final String state = op.getProperties().getProvisioningState();

                if (ProvisioningState.CANCELED.equals(state)
                        || ProvisioningState.FAILED.equals(state)
                        || ProvisioningState.NOTSPECIFIED.equals(state)) {
                    LOGGER.log(Level.INFO, "Failed({0}): {1}:{2}", new Object[] { state, type, resource });
                    baseCommandData.logError(String.format("Failed(%s): %s:%s", state, type, resource));
                    return false;
                } else if (ProvisioningState.SUCCEEDED.equals(state)) {
    		        baseCommandData.logStatus(
    		        		String.format("Succeeded(%s): %s:%s", state, type, resource));
                	completed--;
                } else {
                    LOGGER.log(Level.INFO, "To Be Completed({0}): {1}:{2}", new Object[] { state, type, resource });
                    baseCommandData.logStatus(
    		        		String.format("To Be Completed(%s): %s:%s", state, type, resource));
                }
            }
        } while (completed != 0);
        
        return true;
    }
  }
