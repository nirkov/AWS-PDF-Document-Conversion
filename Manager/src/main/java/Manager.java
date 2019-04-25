import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.InstanceStateChange;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;

import java.io.*;

import java.net.URL;
import java.net.URLConnection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Manager {
    private static final int                   THREAS_NUMBER      = 10;
    private static final String                INSTANCE_URL       = "http://169.254.169.254/latest/meta-data/instance-id";

    private static Map<String,Integer>         tasksMap           = new ConcurrentHashMap<String, Integer>();       //How many tasks left for file.
    private static Map<String,OutputFileMaker> ansMap             = new ConcurrentHashMap<String, OutputFileMaker>();       //The ans for each file

    private static AmazonS3  s3  = AmazonS3ClientBuilder.standard().withRegion("us-east-1").build();  //To work on server
    private static AmazonSQS sqs = AmazonSQSClientBuilder.standard().withRegion("us-east-1").build(); //To work on server
    private static AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").build(); //To work on server

    private static String managerToWorkerQueueUrl = sqs.getQueueUrl("MANAGER_TO_SLAVE").getQueueUrl();
    private static String workerToManagerQueueUrl = sqs.getQueueUrl("SLAVE_TO_MANAGER").getQueueUrl() ;
    private static String managerToLocalQueueUrl  = sqs.getQueueUrl("MANAGER_ANSWER_QUEUE").getQueueUrl() ;
    private static String localToManagerUrl       = sqs.getQueueUrl("MANAGER_TASKS_QUEUE").getQueueUrl() ;


    public static void main(String[] args){
        ExecutorService executor = Executors.newFixedThreadPool(THREAS_NUMBER);

        boolean terminate = false;
        boolean done      = false;

        while (!done){
            // Handle done messages from workers.
            ReceiveMessageRequest receiveWorkerMessageRequest = new ReceiveMessageRequest(workerToManagerQueueUrl)
                    .withMaxNumberOfMessages(10).withMessageAttributeNames("S3Path", "status", "tasksFileName", "PDFUrl", "taskType");
            List<Message> doneMsg = sqs.receiveMessage(receiveWorkerMessageRequest).getMessages();

            for (final Message msg : doneMsg) {
                Map<String, MessageAttributeValue> msgAttributes = msg.getMessageAttributes();
                if (isDoneTask(msgAttributes)) {

                    // Delete msg from SLAVE_TO_MANAER queue
                    sqs.deleteMessage(workerToManagerQueueUrl, msg.getReceiptHandle());

                    // Take all message attributes
                    String bucketName    = msgAttributes.get("S3Path").getStringValue();
                    String status        = msgAttributes.get("status").getStringValue();
                    String tasksFileName = msgAttributes.get("tasksFileName").getStringValue();
                    String originalUrl   = msgAttributes.get("PDFUrl").getStringValue();
                    String taskType      = msgAttributes.get("taskType").getStringValue();

                    if(status.equals("error") && msg.getBody().equals("IO problem")){
                        Message message = createWorkerMsg(taskType, originalUrl, bucketName, tasksFileName);
                        sendMessage(message, managerToWorkerQueueUrl, "try again");
                    }else {

                        //Handle error message that cannot be solved
                        if (status.equals("error")) {
                            ansMap.get(tasksFileName).addLine(taskType, originalUrl, msg.getBody(), status.equals("done")); //Error type is in msg body
                        } else {
                            // Add new line with the corresponding OutputFileMaker
                            ansMap.get(tasksFileName).addLine(taskType, originalUrl, msg.getBody(), status.equals("done"));
                        }
                    }

                    //Update how many tasks left corresponding to this tasksFileName
                    Integer tasksLeft = tasksMap.get(tasksFileName);//get how many tasks left
                    tasksMap.remove(tasksFileName);
                    tasksMap.put(tasksFileName, tasksLeft - 1);

                    if(tasksLeft == 1){ //Done all tasks in tasksFileName so need to send ans and upload outputFile to bucketName in S3

                        tasksMap.remove(tasksFileName);                      //Remove file from tasksMap
                        OutputFileMaker ans = ansMap.remove(tasksFileName);  //Remove file from ansMap and take the answer

                        // Upload file to original bucket in S3
                        String tasksFileNameHTML = tasksFileName.substring(0, tasksFileName.lastIndexOf('.')) + ".html";
                        S3uploadTextFile(ans.getFileAsHTMLFormat(), bucketName, tasksFileNameHTML );

                        // Send message to user about finishing his tasks request
                        Message response = createLocalAppMsg(bucketName , tasksFileNameHTML);   //createLocalAppMsg
                        sendMessage(response, managerToLocalQueueUrl, "done");             //send ans to local
                    }
                }
            }
            if(!terminate){
                //Handle messages from local application
                ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(localToManagerUrl)
                        .withMaxNumberOfMessages(10).withMessageAttributeNames("task", "S3Path", "tasksFileName", "tasksPerWorker");
                List<Message> messages = getMessages(receiveMessageRequest);

                for (final Message msg : messages) {
                    Map<String, MessageAttributeValue> msgAttributes = msg.getMessageAttributes();
                    if(isNewTask(msgAttributes)){

                        // Delete msg from queue
                        sqs.deleteMessage(localToManagerUrl, msg.getReceiptHandle());

                        final String bucketName = msgAttributes.get("S3Path").getStringValue();
                        final String fileName   = msgAttributes.get("tasksFileName").getStringValue();
                        final int    n          = Integer.parseInt(msgAttributes.get("tasksPerWorker").getStringValue());

                        ansMap.put(fileName, new OutputFileMaker());
                        /*
                        ManagerHelper downloads the input file from S3.
                        Creates an SQS message for each URL in the input file together with the operation that should be performed on it
                        Checks the SQS message count and starts Worker processes (nodes) accordingly.
                         */
                        ManagerHelper managerHelper = new ManagerHelper(bucketName, n, fileName, tasksMap);
                        executor.execute(managerHelper);
                    }
                    /*
                    If the message is a termination message, then the manager:
                    Does not accept any more input files from local applications.
                    Waits for all the workers to finish their job, and then terminates them.
                    Creates response messages for the jobs, if needed.
                    Terminates.
                     */
                    else if (isTerminate(msgAttributes)){
                        terminate = true;
                        sqs.deleteMessage(localToManagerUrl, msg.getReceiptHandle());
                        break;
                    }
                }
            }
            if(terminate && tasksMap.isEmpty()){ //terminate + no tasks left => manager can shutdown
                done = true;
                terminate();
            }
        }
    }

    /**
     * Retrieves one or more messages (up to 10), from the specified ReceiveMessageRequest.
     * @param receiveMessageRequest
     * @return List of Message objects
     */
    private static List<Message> getMessages(ReceiveMessageRequest receiveMessageRequest){
        return sqs.receiveMessage(receiveMessageRequest).getMessages();
    }

    /**
     * Return true if the task is done type by checking its attributes.
     * @param msgAttributes - Task attributes
     * @return true or false according to task type
     */
    private static boolean isDoneTask(Map<String, MessageAttributeValue> msgAttributes){
        return (msgAttributes.containsKey("S3Path") &&
                msgAttributes.containsKey("status") &&
                msgAttributes.containsKey("tasksFileName") &&
                msgAttributes.containsKey("taskType") &&
                msgAttributes.containsKey("PDFUrl"));
    }

    /**
     * Return true if the task is terminate type by checking its attributes.
     * @param msgAttributes- Task attributes
     * @return true or false according to task type
     */
    private static boolean isTerminate(Map<String, MessageAttributeValue> msgAttributes){
        return (msgAttributes.containsKey("task") &&
                msgAttributes.get("task").getStringValue().equals("terminate"));
    }

    /**
     * Return true if the task is new task type by checking its attributes.
     * @param msgAttributes - Task attributes
     * @return true or false according to task type
     */
    private static boolean isNewTask(Map<String, MessageAttributeValue> msgAttributes){
        return(msgAttributes.containsKey("task") && msgAttributes.get("task").getStringValue().equals("newTask")
                && msgAttributes.containsKey("S3Path") && msgAttributes.containsKey("tasksPerWorker")
                && msgAttributes.containsKey("tasksFileName"));
    }

    /**
     * Create Message object and add attributes to the message according to worker message parameters.
     * @param taskType  - message attribute
     * @param url - message attribute
     * @param bucketName - message attribute
     * @param fileName - message attribute
     * @return worker message object.
     */
    private static Message createWorkerMsg(String taskType, String url, String bucketName, String fileName){
        Message msg = new Message();
        addMessageAttribute(msg,"taskType", taskType, "String.taskType");
        addMessageAttribute(msg,"PDFUrl", url, "String.PDFUrl");
        addMessageAttribute(msg,"bucketName", bucketName, "String.bucketName");
        addMessageAttribute(msg,"tasksFileName", fileName, "String.tasksFileName");
        return msg;
    }

    /**
     * Create Message object and add attributes to the message according to local app message parameters.
     * @param path - message attribute
     * @param tasksFileName - message attribute
     * @return local app message object.
     */
    private static Message createLocalAppMsg(String path, String tasksFileName){
        Message msg = new Message();
        addMessageAttribute(msg,"S3path", path, "String.S3path");
        addMessageAttribute(msg,"tasksFileName", tasksFileName, "String.tasksFileName");
        return msg;
    }

    /**
     *  Add attribute to Message object.
     * @param msg - Message object.
     * @param key - Message key.
     * @param attribute - Message attribute.
     * @param dataType - Attribute type.
     */
    private static void addMessageAttribute(Message msg, String key, String attribute, String dataType){
        Map messageAttributes = msg.getMessageAttributes();
        messageAttributes.put(key, new MessageAttributeValue().withDataType(dataType).withStringValue(attribute));
    }

    /**
     * Create message request and send it to queue url
     * @param message - message to send.
     * @param queueUrl - url of the queue.
     * @param body - the message body.
     */
    private static void sendMessage(Message message, String queueUrl, String body){
        SendMessageRequest send_msg_request = new SendMessageRequest()
                .withQueueUrl(queueUrl)
                .withMessageAttributes(message.getMessageAttributes())
                .withMessageBody(body);

        sqs.sendMessage(send_msg_request);
    }

    /**
     * Upload txt file to S3 bucket
     * @param txtFile - The file to upload.
     * @param bucketName - The bucket to upload to.
     * @param fileName - File name.
     * @return true or false if upload success.
     */
    private static boolean S3uploadTextFile(String txtFile, String bucketName, String fileName){
        InputStream byteStream = new ByteArrayInputStream((txtFile).getBytes());
        ObjectMetadata metadataS3File = new ObjectMetadata();
        metadataS3File.setContentLength(txtFile.getBytes().length);
        PutObjectRequest uploadRequest = new PutObjectRequest(bucketName, fileName, byteStream, metadataS3File);

        // withCannedAcl - Sets the optional pre-configured access control policy to use for the newobject
        s3.putObject(uploadRequest.withCannedAcl(CannedAccessControlList.PublicRead));

        return (s3.getUrl(bucketName, fileName).getFile().isEmpty()) ? false : true;
    }

    // TODO: no need it in cloud
    private static AWSStaticCredentialsProvider credentialsProvider() {
        return new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());
    }

    /**
     * Send terminate messages to all workers and then terminate the manager.
     */
    private static void terminate(){
        // Send message to terminate workers
        Message termWorker = createWorkerMsg("terminate","terminate","terminate","terminate");
        int numOfActiveWorkers = new ManagerHelper().getNumOfActiveWorkers();
        for(int i = 0; i < numOfActiveWorkers; i++) {
            sendMessage(termWorker, managerToWorkerQueueUrl, "terminate");
        }
        // Terminate manager.
        try {
            List<String> instances = new LinkedList<String>(); //A list of instance ids as strings.
            instances.add(retrieveInstanceId());
            TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest();
            terminateInstancesRequest.setInstanceIds(instances);
            TerminateInstancesResult terminateInstancesResult = ec2.terminateInstances(terminateInstancesRequest);
            //Wait for instance to terminate
            List<InstanceStateChange> terminateResult;
            terminateResult = terminateInstancesResult.getTerminatingInstances();
            for(InstanceStateChange change : terminateResult){
                while (!change.getCurrentState().getName().equals("terminated")){
                    try{
                        Thread.sleep(5000);
                        System.out.println(change.getCurrentState().getName() + " "
                                + change.getCurrentState().getCode());
                    }catch (InterruptedException e){
                        System.out.println(e.getMessage());
                    }
                }
            }
        } catch (IOException e){
            System.out.println(e.getMessage());
        }
    }

    /**
     * Get EC2 instance id
     * @return String of EC2 id value
     * @throws IOException
     */
    public static String retrieveInstanceId() throws IOException {
        String EC2Id = null;
        String inputLine;
        URL EC2MetaData = new URL(INSTANCE_URL);
        URLConnection EC2MD = EC2MetaData.openConnection();
        BufferedReader in = new BufferedReader(new InputStreamReader(EC2MD.getInputStream()));
        while ((inputLine = in.readLine()) != null) {
            EC2Id = inputLine;
        }
        in.close();
        return EC2Id;
    }

    public static String extractFileName(String urlToPDF){
        if(urlToPDF.isEmpty()) throw new IllegalArgumentException("The url is enmpty");
        return urlToPDF.contains("/") ? urlToPDF.substring( urlToPDF.lastIndexOf('/')+1, urlToPDF.lastIndexOf('.'))
                : urlToPDF.substring(0, urlToPDF.lastIndexOf('.'));
    }
}
