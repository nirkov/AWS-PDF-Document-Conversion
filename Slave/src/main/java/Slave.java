
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.InstanceStateChange;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;

import javax.swing.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URL;

import java.net.URLConnection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Slave {
    static private final String MANAGER_TO_SLAVE = "MANAGER_TO_SLAVE";
    static private final String SLAVE_TO_MANAGER = "SLAVE_TO_MANAGER";

    private static AmazonEC2 mEC2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").build();
    static private AmazonS3  mS3  = AmazonS3ClientBuilder.standard().withRegion("us-east-1").build();
    static private AmazonSQS mSQS = AmazonSQSClientBuilder.standard().withRegion("us-east-1").build();

    static private TextParsingUtils mTextParsingUtils = new TextParsingUtils();

    public static void main(String[] args) {
        final String tasksQueueUrl = mSQS.getQueueUrl(MANAGER_TO_SLAVE).getQueueUrl();
        final String answerQueueUrl = mSQS.getQueueUrl(SLAVE_TO_MANAGER).getQueueUrl();

        int milliseconds = 1;
        boolean stop = false;
        Map<String, String> tasksTypeSuffix = new HashMap<String, String>();
        tasksTypeSuffix.put("ToHTML", ".html");
        tasksTypeSuffix.put("ToImage", ".png");
        tasksTypeSuffix.put("ToText", ".txt");

        while(!stop){
            // get message
            List<Message> messages;
            do{                                                       // take msg from task queue
                try {
                    TimeUnit.MILLISECONDS.sleep(milliseconds);        // if fail in nth try, wait for n seconds.
                    if(milliseconds < 5001) milliseconds += 250;      // wait maximum 5 seconds.
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(tasksQueueUrl)
                        .withMessageAttributeNames("taskType", "PDFUrl", "bucketName", "tasksFileName") ;
                messages = mSQS.receiveMessage(receiveMessageRequest).getMessages();
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }while(messages.isEmpty());                            // if the list is not empty go on

            milliseconds = 1;
            final Message message = messages.get(0);

            // take message reciept handle for the listener and for future delete
            final String messageRecieptHandle = message.getReceiptHandle();

            //Set timer to give more 30 sec visibility after 25 sec
            final int delay =  25000;  //25 sec
            ActionListener taskPerformer = new ActionListenerImp(messageRecieptHandle, mSQS, tasksQueueUrl);
            new Timer(delay, taskPerformer).start();

            // parsing the message attributes.
            Map<String, MessageAttributeValue> msgAttributes = message.getMessageAttributes();

            if(!msgAttributes.get("taskType").getStringValue().equals("terminate")){    // Check for terminating sign
                String mTaskType, mPDFurl, mBucketName, mFileName, mTasksFileName;
                try{
                    mTaskType      = msgAttributes.get("taskType").getStringValue();
                    mPDFurl        = msgAttributes.get("PDFUrl").getStringValue();
                    mBucketName    = msgAttributes.get("bucketName").getStringValue();
                    mFileName      = mTextParsingUtils.extractFileName(mPDFurl) + tasksTypeSuffix.get(mTaskType);
                    mTasksFileName = msgAttributes.get("tasksFileName").getStringValue();
                }catch (Exception e){
                    mSQS.deleteMessage(new DeleteMessageRequest(tasksQueueUrl, messageRecieptHandle));
                    System.out.println(e.getMessage());
                    continue;
                }

                try{
                    // execute the task
                    final boolean executed = executeTask(mTaskType, mPDFurl, mBucketName, mFileName);

                    // send msg to SLAVE_TO_MANAGER
                    final String s3FileUrl = "https://" + mBucketName + ".s3.amazonaws.com/" + mFileName;
                    msgToManager(mBucketName, executed ? "done" : "error", mPDFurl,
                                mTasksFileName, mTaskType, answerQueueUrl, executed ? s3FileUrl : "error");

                    // delete msg after excute it
                    mSQS.deleteMessage(new DeleteMessageRequest(tasksQueueUrl, messageRecieptHandle));

                }catch(Exception e){
                    // If an error occurred with body message "try again"
                    // we returning the message to manager with the body and the manager
                    // take it as task that cannot be done.
                    if( message.getBody().equals("try again")){
                        mSQS.deleteMessage(new DeleteMessageRequest(tasksQueueUrl, messageRecieptHandle));
                        msgToManager(mBucketName,"error", mPDFurl, mTasksFileName, mTaskType, answerQueueUrl,
                                message.getBody());
                    }else {
                        mSQS.deleteMessage(new DeleteMessageRequest(tasksQueueUrl, messageRecieptHandle));
                        msgToManager(mBucketName,"error", mPDFurl, mTasksFileName, mTaskType, answerQueueUrl,
                                e.getMessage());
                    }
                }
            }else{
                mSQS.deleteMessage(new DeleteMessageRequest(tasksQueueUrl, messageRecieptHandle));
                stop = true;
            }
        }
        terminate();
    }

    public static String retrieveInstanceId() throws IOException {
        String EC2Id = null;
        String inputLine;
        URL EC2MetaData = new URL("http://169.254.169.254/latest/meta-data/instance-id");
        URLConnection EC2MD = EC2MetaData.openConnection();
        BufferedReader in = new BufferedReader(new InputStreamReader(EC2MD.getInputStream()));
        while ((inputLine = in.readLine()) != null) {
            EC2Id = inputLine;
        }
        in.close();
        return EC2Id;
    }

    private static void msgToManager(String s3Path, String status, String PDFUrl, String tasksFileName, String taskType, String qUrl, String body){

        Map messageAttributes = new HashMap();
        messageAttributes.put("S3Path", new MessageAttributeValue().withDataType("String.S3Path").withStringValue(s3Path));
        messageAttributes.put("status", new MessageAttributeValue().withDataType("String.status").withStringValue(status));
        messageAttributes.put("taskType", new MessageAttributeValue().withDataType("String.taskType").withStringValue(taskType));
        messageAttributes.put("PDFUrl", new MessageAttributeValue().withDataType("String.PDFUrl").withStringValue(PDFUrl));
        messageAttributes.put("tasksFileName", new MessageAttributeValue().withDataType("String.tasksFileName").withStringValue(tasksFileName));

        Message msg = new Message();
        msg.getMessageAttributes().putAll(messageAttributes);

        SendMessageRequest msgRequest = new SendMessageRequest()
                .withQueueUrl(qUrl)
                .withMessageAttributes(msg.getMessageAttributes())
                .withMessageBody(body);

        mSQS.sendMessage(msgRequest);
    }

    private static boolean executeTask(String mTaskType, String mPDFurl, String mBucketName, String mFileName ) throws Exception {
        if(mTaskType.equals("ToImage")) return executeTaskToImage(mPDFurl, mBucketName, mFileName);
        if(mTaskType.equals("ToHTML"))  return executeTaskToHTML(mPDFurl, mBucketName, mFileName);
        if(mTaskType.equals("ToText"))  return executeTaskToText(mPDFurl, mBucketName, mFileName);
        return false;
    }

    private static boolean executeTaskToHTML(String mPDFurl, String mBucketName, String mFileName) throws Exception{
        try {
            String txtFile = mTextParsingUtils.PdfToHTML(mPDFurl);
            return S3uploadTextFile(txtFile, mBucketName, mFileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean executeTaskToImage(String mPDFurl, String mBucketName, String mFileName) throws Exception {
        String localPathToImage = mTextParsingUtils.PdfToImage(mPDFurl, mFileName);
        return S3uploadFromLocal(localPathToImage, mBucketName, mFileName);
    }

    private static boolean executeTaskToText(String mPDFurl, String mBucketName, String mFileName) throws Exception {
        String txtFile = mTextParsingUtils.PdfToTxt(mPDFurl);
        return S3uploadTextFile(txtFile, mBucketName, mFileName);

    }

    private static boolean S3uploadTextFile(String txtFile, String bucketName, String fileName){
        InputStream byteStream = new ByteArrayInputStream((txtFile).getBytes());
        ObjectMetadata metadataS3File = new ObjectMetadata();
        metadataS3File.setContentLength(txtFile.getBytes().length);
        PutObjectRequest uploadRequest = new PutObjectRequest(bucketName, fileName, byteStream, metadataS3File);

        // withCannedAcl - Sets the optional pre-configured access control policy to use for the newobject
        mS3.putObject(uploadRequest.withCannedAcl(CannedAccessControlList.PublicRead));

        return (mS3.getUrl(bucketName, fileName).getFile().isEmpty()) ? false : true;
    }

    private static boolean S3uploadFromLocal(String path, String bucketName, String fileName){
        // Upload image to S3
        File file = new File(path);
        PutObjectRequest uploadRequest = new PutObjectRequest(bucketName, fileName, file);
        mS3.putObject(uploadRequest.withCannedAcl(CannedAccessControlList.PublicRead));

        // Delete the file from local
        if(file.delete()){
            System.out.println(file.getName() + " is deleted!");
        }else{
            System.out.println("Delete operation is failed.");
        }

        // return true if the file exist in S3 bucket.
        return true; //TODO: return (mS3.getUrl(bucketName, fileName).getFile().isEmpty()) ? false : true;
    }

    private static void deleteMessageSQS(Message message, String SQSUrl) {
        String messageRecieptHandle = message.getReceiptHandle();
        mSQS.deleteMessage(new DeleteMessageRequest(SQSUrl, messageRecieptHandle));
    }

    // TODO : for run the slave localy need attach this function to initialization of the amazon EC2, SQS and S3 fields.
    private static AWSStaticCredentialsProvider credentialsProvider() {
        return new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());
    }

    private static void terminate(){
        try {
            List<String> instances = new LinkedList<String>(); // List of instance ids as string.
            instances.add(retrieveInstanceId());
            TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest();
            terminateInstancesRequest.setInstanceIds(instances);
            TerminateInstancesResult terminateInstancesResult = mEC2.terminateInstances(terminateInstancesRequest);
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
}

