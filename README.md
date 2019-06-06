# ☁️ AWS-PDF-Document-Conversion ☁️
Distributively process a list of PDF files, perform some operations on them, and display the result on a web page.

## Details:
The application is composed of a local application and instances running on the Amazon cloud. The application will get as an input a text file containing a list of URLs of PDF files with an operation to perform on them. Then, instances will be launched in AWS (slaves). Each slave will download PDF files, perform the requested operation, and display the result of the operation on a webpage.
The use-case is as follows:

User starts the application and supplies as input a file with URLs of PDF files together with operations to perform on them, an integer n stating how many PDF files per slave, and an optional argument terminate, if received the local application sends a terminate message to the Manager.
User gets back an html file containing PDF files after the result of the operation performed on them.

## Input File Format:
Each line in the input file will contain an operation followed by a tab ("\t") and a URL of a pdf file. The operation can be one of the following:
 - ToImage - convert the first page of the PDF file to a "png" image.
 - ToHTML - convert the first page of the PDF file to an HTML file.
 - ToText - convert the first page of the PDF file to a text file.
 
Example to input file: [GitHub Pages](https://github.com/nirkov/AWS-PDF-Document-Conversion/blob/master/input.txt).

## Output file format:
The output is an HTML file containing a line for each input line. The format of each line is as follows:
> operation: input file ,output file url.
 
 where:
 - Operation is one of the possible operations.
 - Input file is a link to the input PDF file.
 - Output file is a link to the image/text/HTML output file.

If an exception occurs while performing an operation on a PDF file, or the PDF file is not available, then output line for this file will be: 
> operation: input file,  a short description of the exception.

## LocalApp:
The application resides on a local (non-cloud) machine. Once started, it reads the input file from the user, and:
 - Checks if a Manager node is active on the EC2 cloud. If it is not, the application will start the manager node.
 - Uploads the file to S3.
 - Sends a message to an SQS queue, stating the location of the file on S3
 - Checks an SQS queue for a message indicating the process is done and the response (the summary file) is available on S3.
 - Downloads the summary file from S3, and create an html file representing the results.
 - Sends a termination message to the Manager if it was supplied as one of its input arguments.
 
 ## Manager:
The manager process resides on an EC2 node. It checks a special SQS queue for messages from local applications. Once it receives a message it:
1.If the message is that of a new task it:
  - Downloads the input file from S3.
  - Creates an SQS message for each URL in the input file together with the operation that should be performed on it.
  - Checks the SQS message count and starts slave processes (nodes) accordingly.
    - The manager should create a slave for every n messages, if there are no running slaves.
    - If there are k active slaves, and the new job requires m slaves, then the manager should create m-k new slaves, if possible.
    - Note that while the manager creates a node for every n messages, it does not delegate messages to specific nodes. All of the             slave nodes take their messages from the same SQS queue; so it might be the case that with 2n messages, hence two slave nodes,         one node processed n+(n/2) messages, while the other processed only n/2.

2.If the message is a termination message, then the manager:
  - Does not accept any more input files from local applications.
  - Waits for all the slaves to finish their job, and then terminates them.
  - Creates response messages for the jobs, if needed.
  - Terminates.

## Slave
A slave process resides on an EC2 node. Its life cycle is as follows:
Repeatedly:
 - Get a message from an SQS queue.
 - Download the PDF file indicated in the message.
 - Perform the operation requested on the file.
 - Upload the resulting output file to S3.
 - Put a message in an SQS queue indicating the original URL of the PDF, the S3 url of the new image file, and the operation that was      performed.
 - remove the processed message from the SQS queue.
 
**IMPORTANT**:
- If an exception occurs, then the slave should recover from it, send a message to the manager of the input message that caused the       exception together with a short description of the exception, and continue working on the next message.
- If a slave stops working unexpectedly before finishing its work on a message, then some other slave should be able to handle that     message.

## System Summary
1. Local Application uploads the file with the list of PDF files and operations to S3.
2. Local Application sends a message (queue) stating the location of the input file on S3.
3. Local Application does one of the two:
  - Starts the manager.
  - Checks if a manager is active and if not, starts it.
4. Manager downloads list of PDF files together with the operations.
5. Manager creates an SQS message for each URL and operation from the input list.
6. Manager bootstraps nodes to process messages.
7. Worker gets a message from an SQS queue.
8. Worker downloads the PDF file indicated in the message.
9. Worker performs the requested operation on the PDF file, and uploads the resulting output to S3.
10. Worker puts a message in an SQS queue indicating the original URL of the PDF file and the S3 URL of the output file, together with the operation that produced it.
11. Manager reads all Workers' messages from SQS and creates one summary file, once all URLs in the input file have been processed.
12. Manager uploads the summary file to S3.
13. Manager posts an SQS message about the summary file.
14. Local Application reads SQS message.
15. Local Application downloads the summary file from S3.
16. Local Application creates html output file.
17. Local application send a terminate message to the manager if it received terminate as one of its arguments.


![image](https://user-images.githubusercontent.com/32679759/59013480-bc4ea300-8842-11e9-9e09-fb6f3ce8de93.png)
