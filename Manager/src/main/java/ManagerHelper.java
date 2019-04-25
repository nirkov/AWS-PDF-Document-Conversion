import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.util.Base64;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagerHelper implements Runnable{

    private static AmazonS3  s3  = AmazonS3ClientBuilder.standard().withRegion("us-east-1").build();
    private static AmazonSQS sqs = AmazonSQSClientBuilder.standard().withRegion("us-east-1").build();
    private static AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").build();

    private final static Object lock = new Object();
    private final String  workerJobsQueueUrl = sqs.getQueueUrl("MANAGER_TO_SLAVE").getQueueUrl() ;

    private static AtomicInteger numOfActiveWorkers = new AtomicInteger(0);

    private String               bucketName;
    private String               fileName;
    private int                  tasksPerWorker;
    private Map<String, Integer> tasksMap;

    ManagerHelper(){}

    ManagerHelper(String bucketName, int tasksPerWorker, String fileName, Map<String, Integer> tasksMap) {
        this.bucketName     = bucketName;
        this.fileName       = fileName;
        this.tasksPerWorker = tasksPerWorker;
        this.tasksMap       = tasksMap;
    }
    /*
   Downloads the input file from S3.
   Creates an SQS message for each URL in the input file together with the operation that
   should be performed on it
   Checks the SQS message count and starts Worker processes (nodes) accordingly.
    */
    public void run() {
        // Download from S3
        S3Object object = s3.getObject(new GetObjectRequest(bucketName, fileName));

        // Parse the file to MsgAsPair with taskType and PDFUrl
        List<MsgAsPair<String>>  tasksList = null;
        try {
            tasksList = (new TextParsingUtils()).ParsTextToPairs(object.getObjectContent());
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (MsgAsPair<String> msgAsPair : tasksList){
            Message msg = createWorkerMsg(msgAsPair.getTask(), msgAsPair.getPdfPath(), bucketName, fileName);
            sendMessage(msg, workerJobsQueueUrl);
        }

        int createdWorkers = 0;
        int numOfTasks = tasksList.size();
        tasksMap.put(fileName, numOfTasks); //Add the task to map;

        try{
            synchronized(lock){
                int workersToCreate =  (numOfTasks / tasksPerWorker) - numOfActiveWorkers.get();
                if(workersToCreate > 0){
                    System.out.println("Creating " + workersToCreate + " workers");
                    createdWorkers = createWorkers(workersToCreate);
                    if(createdWorkers > 0) updateActiveWorkers(createdWorkers);
                }
            }
        }catch(Exception e){
            System.out.println(e.getMessage());
        }
    }

    public int getNumOfActiveWorkers(){
        return numOfActiveWorkers.get();
    }

    /**
     * Update the number of active workers, using compareAndSet function.
     */
    private void updateActiveWorkers (int delta){
        int current, next;
        do {
            current = numOfActiveWorkers.get();
            next = current + delta;
        } while (!numOfActiveWorkers.compareAndSet(current, next));
    }

    /**
     * Try to create workers by creating Ec2 instances.
     * If the number of workers to create is too big it will create the biggest number that it can create.
     * @param workersToCreate - number of workers to create.
     */
    private int createWorkers(int workersToCreate){
        int numberOfInstancesWhichCreated = 0;
        if (workersToCreate > 0){
            //create workers
            try {
                RunInstancesRequest request = new RunInstancesRequest("ami-0080e4c5bc078760e", 1, workersToCreate);
                request.setInstanceType(InstanceType.T1Micro.toString());

                // Add Tag to worker with "Worker" value and key.
                Tag tag = new Tag("Worker", "Worker");
                TagSpecification tagInfo = new TagSpecification().withTags(tag).withResourceType("instance");
                request.setTagSpecifications(java.util.Arrays.asList(tagInfo));

                // Add download from S3 and run script for Manager jar file.//TODO change path if need
                String managerScript = "#!/bin/bash" + "\n" + "aws s3 cp s3://lunchslaveinstancecodebucket/Slave.jar Slave.jar\n" + "java -jar Slave.jar\n";
                String workerScriptBase64 = null;
                try {
                    workerScriptBase64 = new String(Base64.encode(managerScript.getBytes("UTF-8")), "UTF-8");
                } catch (UnsupportedEncodingException e) {}
                request.setUserData(workerScriptBase64);

                // Add IAM role to worker EC2 instance.
                IamInstanceProfileSpecification workerRoles = new IamInstanceProfileSpecification();
                workerRoles.setName("ManagerPermissionsRole");    // Give the slaves a manager permission for can he terminate himself
                request.setIamInstanceProfile(workerRoles);

                RunInstancesResult instances = ec2.runInstances(request);
                numberOfInstancesWhichCreated = instances.getReservation().getInstances().size();
                System.out.println("Launch instances: " + instances);

            } catch (AmazonServiceException ase) {
                System.out.println("---------------------------------------------------");
                System.out.println("-----       ERROR IN CREATING INSTANCES       -----");
                System.out.println("---------------------------------------------------");
                System.out.println("Caught Exception: " + ase.getMessage());
                System.out.println("Reponse Status Code: " + ase.getStatusCode());
                System.out.println("Error Code: " + ase.getErrorCode());
                System.out.println("Request ID: " + ase.getRequestId());
            }
        }
        return numberOfInstancesWhichCreated;
    }

    /**
     * Send message to sqs queue
     * @param message - The message to send.
     * @param queueUrl - The queue url that receive the message.
     */
    private void sendMessage(Message message, String queueUrl){
        SendMessageRequest send_msg_request = new SendMessageRequest()
                .withQueueUrl(queueUrl)
                .withMessageAttributes(message.getMessageAttributes())
                .withMessageBody("body");

        sqs.sendMessage(send_msg_request);
    }

    /**
     * Create message to worker with the necessary attributes.
     * @param taskType - Message attribute value.
     * @param url - Message attribute value.
     * @param bucketName - Message attribute value.
     * @param fileName - Message attribute value.
     * @return
     */
    private Message createWorkerMsg(String taskType, String url, String bucketName, String fileName){
        Message msg = new Message();
        addMessageAttribute(msg,"taskType", taskType, "String.taskType");
        addMessageAttribute(msg,"PDFUrl", url, "String.PDFUrl");
        addMessageAttribute(msg,"bucketName", bucketName, "String.bucketName");
        addMessageAttribute(msg,"tasksFileName", fileName, "String.tasksFileName");
        return msg;
    }

    /**
     * Add one attribute to message.
     * @param msg - The message that need the attribute.
     * @param key - Attribute key.
     * @param attribute - Attribute value.
     * @param dataType - Attribute type.
     */
    private void addMessageAttribute(Message msg, String key, String attribute, String dataType){
        Map messageAttributes = msg.getMessageAttributes();
        messageAttributes.put(key,
                new MessageAttributeValue()
                        .withDataType(dataType)
                        .withStringValue(attribute));
    }

    // TODO: not need it for running in cloud.
    private static AWSStaticCredentialsProvider credentialsProvider() {
        return new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());
    }
}
