import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import com.amazonaws.util.Base64;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;


public class AWSUtils {

    // static variable single_instance of type Singleton 
    private static AWSUtils aws = null;

    private final String MANAGER_TASKS_QUEUE           = "MANAGER_TASKS_QUEUE";
    private final String MANAGER_ANSWER_QUEUE          = "MANAGER_ANSWER_QUEUE";
    private final String MANAGER_JAR_CONTAINING_BUCKET = "lunchmanagerinstancecodebucket";

    // static method to create instance of Singleton class 
    public static AWSUtils getInstance() {
        if (aws == null) aws = new AWSUtils();
        return aws;
    }

    // fields
    private AmazonEC2 mEC2;
    private AmazonS3  mS3;
    private AmazonSQS mSQS;
    private Message   mManagerKey;
    private String    MANAGER_KEY_QUEUE_URL;

    // private constructor restricted to this class itself 
    private AWSUtils() {
        mEC2 = AmazonEC2ClientBuilder.standard()
                .withCredentials(credentialsProvider())
                .withRegion("us-east-1")
                .build();

        mS3  = AmazonS3ClientBuilder.standard()
                .withCredentials(credentialsProvider())
                .withRegion("us-east-1")
                .build();

        mSQS = AmazonSQSClientBuilder.standard()
                .withCredentials(credentialsProvider())
                .withRegion("us-east-1")
                .build();

        MANAGER_KEY_QUEUE_URL = mSQS.getQueueUrl("MANAGER_KEY_QUEUE").getQueueUrl();
        mManagerKey = null;

    }

    public void crateNewBucketIfNeed(String bucketName) {
        try {
            if (mS3.doesBucketExistV2(bucketName)) {
                System.out.format("Bucket %s already exists.\n", bucketName);
            } else {
                mS3.createBucket(bucketName);
            }
        } catch (AmazonS3Exception e) {
            System.err.println(e.getErrorMessage());
        }
    }

    private AWSStaticCredentialsProvider credentialsProvider() {
        return new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());
    }

    public boolean runTheManager() {
        // Check if the key for running the manager is free
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(MANAGER_KEY_QUEUE_URL);
        List<Message> messages = mSQS.receiveMessage(receiveMessageRequest).getMessages();
        if(!messages.isEmpty()){  // take the key and run the manager
            mManagerKey = messages.get(0);
            String messageRecieptHandle = messages.get(0).getReceiptHandle();
            mSQS.deleteMessage(new DeleteMessageRequest(MANAGER_KEY_QUEUE_URL, messageRecieptHandle));
            return true;
        }else{                    // the key isnt free so the manager already running
            return false;
        }
    }

    public void ManagerUp(String iamName, String instanceTag) {
        if(runTheManager()) {
            try {
                //Create all queues
                createQueues(Arrays.asList("MANAGER_TASKS_QUEUE", "MANAGER_ANSWER_QUEUE", "MANAGER_TO_SLAVE", "SLAVE_TO_MANAGER"));

                RunInstancesRequest request = new RunInstancesRequest("ami-0080e4c5bc078760e", 1, 1);
                request.setInstanceType(InstanceType.T1Micro.toString());

                // Add Tag to manager with "Manager" value and key.
                Tag tag = new Tag(instanceTag, instanceTag);
                TagSpecification tagInfo = new TagSpecification().withTags(tag).withResourceType("instance");
                request.setTagSpecifications(java.util.Arrays.asList(tagInfo));

                // Add download from S3 and run script for Manager jar file.
                String script = "#!/bin/bash" + "\n" +
                        "aws s3 cp s3://"+MANAGER_JAR_CONTAINING_BUCKET+"/Manager.jar Manager.jar" + "\n" +
                        "java -jar Manager.jar\n";
                String managerScriptBase64 = null;
                try {
                    managerScriptBase64 = new String( Base64.encode( script.getBytes( "UTF-8" )), "UTF-8" );
                } catch (UnsupportedEncodingException e) {}
                request.setUserData(managerScriptBase64);

                // Add IAM role to manager EC2 instance.
                IamInstanceProfileSpecification instanceRoles = new IamInstanceProfileSpecification();
                instanceRoles.setName(iamName);
                request.setIamInstanceProfile(instanceRoles);

                List<Instance> instances = mEC2.runInstances(request).getReservation().getInstances();
                System.out.println("Launch instances: " + instances);

            } catch (AmazonServiceException ase) {
                System.out.println("Caught Exception: " + ase.getMessage());
                System.out.println("Reponse Status Code: " + ase.getStatusCode());
                System.out.println("Error Code: " + ase.getErrorCode());
                System.out.println("Request ID: " + ase.getRequestId());
            }
        }else System.out.println("Manager already run");
    }

    private void createQueues(List<String> queuesName) {
        for (String name : queuesName){
            final CreateQueueRequest createQueueRequest = new CreateQueueRequest(name);
            mSQS.createQueue(name);
            final String myQueueUrl = mSQS.createQueue(createQueueRequest)
                    .getQueueUrl();
            if(myQueueUrl != null && myQueueUrl.isEmpty()){
                System.out.println("SQS queue: " + name + " is up.");
            }
        }
    }

    public void openBucket(String bucketName){
        crateNewBucketIfNeed(bucketName);
    }

    public List<String> uploadTasksFileToS3(String dirPath, String bucketName, String role) {
        // create pre running script

        // start manager with the script and ManagerPermissionsRole
        ManagerUp(role, "Manager");

        // create user bucket
        crateNewBucketIfNeed(bucketName);
        List<String> filesName = new ArrayList<>();
        File dir = new File(dirPath);
        String key;

        for (File file : dir.listFiles()) {
            if(file.getName().endsWith(".txt")){
                key = file.getName().replace('\\', '_').replace('/','_').replace(':', '_');
                PutObjectRequest req = new PutObjectRequest(bucketName, key, file);
                mS3.putObject(req);
                filesName.add(file.getName());
            }
        }

        return filesName;
    }

    public void msgToManager(String bucketName, List<String> filesName, String masgType, String n){
        for(String fileName: filesName){
            Message msg = new Message();
            Map messageAttributes = new HashMap();
            messageAttributes.put("task", new MessageAttributeValue().withDataType("String.task").withStringValue(masgType));
            messageAttributes.put("S3Path", new MessageAttributeValue().withDataType("String.S3Path").withStringValue(bucketName));
            messageAttributes.put("tasksFileName", new MessageAttributeValue().withDataType("String.tasksFileName").withStringValue(fileName));
            messageAttributes.put("tasksPerWorker", new MessageAttributeValue().withDataType("String.tasksPerWorker").withStringValue(n));
            msg.getMessageAttributes().putAll(messageAttributes);

            SendMessageRequest msgRequest = new SendMessageRequest()
                    .withQueueUrl(mSQS.getQueueUrl(MANAGER_TASKS_QUEUE).getQueueUrl())
                    .withMessageAttributes(msg.getMessageAttributes())
                    .withMessageBody("tasks file");
            mSQS.sendMessage(msgRequest);
        }
    }

    public void sendTerminate(){
        // Send to manager terminate massage
        //if(mManagerKey != null){
        List<String> terminate = new ArrayList<>();
        terminate.add("terminate");
        msgToManager("terminate", terminate , "terminate", "terminate");
        //}

        // Wait until the manager instance terminated
        int managerStatus;
        do{
            try {
                TimeUnit.MILLISECONDS.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            managerStatus = getManagerStatus();
        }while(managerStatus != 48);

        // Return the MANAGER KEY to the queue for another local app can run the manager in future
        SendMessageRequest msgRequest = new SendMessageRequest()
                .withQueueUrl(MANAGER_KEY_QUEUE_URL)
                .withMessageBody("MANAGER KEY");
        mSQS.sendMessage(msgRequest);
    }

    private int getManagerStatus(){
        List<Reservation> reservation = mEC2.describeInstances().getReservations();
        for(Reservation res : reservation) {
            List<Instance> instances = res.getInstances();
            for(Instance inst : instances) {
                List<Tag> tags = inst.getTags();
                for(Tag tag : tags) {
                    if(tag.getKey().equals("Manager")){
                        return inst.getState().getCode();
                    }
                }
            }
        }
        return -1;
    }

    public void getOutputFile(String bucketName, List<String> filesName, String outputFilePath) {
        int milliseconds = 1;
        List<String> toDelete = new ArrayList<>();
        toDelete.addAll(filesName);
        final String managerAnsQueueUrl = mSQS.getQueueUrl(MANAGER_ANSWER_QUEUE).getQueueUrl();

        while(!filesName.isEmpty()){
            List<Message> messages;
            do{                                                       // take msg from task queue
                try {
                    TimeUnit.MILLISECONDS.sleep(milliseconds);        // if fail in nth try, wait for n seconds.
                    if(milliseconds < 5001) milliseconds += 250;      // wait maximum 5 seconds.
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(managerAnsQueueUrl)
                        .withMaxNumberOfMessages(5).withMessageAttributeNames("S3path", "tasksFileName") ;
                messages = mSQS.receiveMessage(receiveMessageRequest).getMessages();
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }while(messages.isEmpty());                            // if the list is not empty go on
            milliseconds = 1;

            for(Message msg : messages){
                Map<String, MessageAttributeValue> msgAttributes = msg.getMessageAttributes();
                for(String fileName : filesName){
                    String fileNameHTML = fileName.substring(0, fileName.lastIndexOf('.')) + ".html";
                    if(msgAttributes.containsKey("tasksFileName") &&
                       msgAttributes.get("tasksFileName").getStringValue().equals(fileNameHTML)){
                        // delete message
                        String messageRecieptHandle = msg.getReceiptHandle();
                        mSQS.deleteMessage(new DeleteMessageRequest(managerAnsQueueUrl, messageRecieptHandle));

                        // To delete from filesName
                        toDelete.remove(fileName);

                        // Download S3 file
                        S3Object fetchFile = mS3.getObject(new GetObjectRequest(bucketName, fileName));
                        InputStream objectData = fetchFile.getObjectContent();

                        // Crate new file in local path
                        File file = new File((outputFilePath + fileNameHTML).replace("\\", "/"));

                        // Copy to file.
                        try {
                            Files.copy(objectData, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            objectData.close();
                        } catch (IOException e) {
                            System.out.println("Can't save the file : " + fileNameHTML + " in path : " + outputFilePath);
                        }
                        break;
                    }
                }
                filesName = toDelete;
                if(filesName.isEmpty()){
                    break;
                }
            }
        }
    }
}

