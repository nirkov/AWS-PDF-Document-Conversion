# AWS-PDF-Document-Conversion
Distributively process a list of PDF files, perform some operations on them, and display the result on a web page.

## Details:
The application is composed of a local application and instances running on the Amazon cloud. The application will get as an input a text file containing a list of URLs of PDF files with an operation to perform on them. Then, instances will be launched in AWS (workers). Each worker will download PDF files, perform the requested operation, and display the result of the operation on a webpage.
The use-case is as follows:

User starts the application and supplies as input a file with URLs of PDF files together with operations to perform on them, an integer n stating how many PDF files per worker, and an optional argument terminate, if received the local application sends a terminate message to the Manager.
User gets back an html file containing PDF files after the result of the operation performed on them.

## Input File Format:
Each line in the input file will contain an operation followed by a tab ("\t") and a URL of a pdf file. The operation can be one of the following:
 - ToImage - convert the first page of the PDF file to a "png" image.
 - ToHTML - convert the first page of the PDF file to an HTML file.
 - ToText - convert the first page of the PDF file to a text file.
 
Example to input file: [GitHub Pages](https://github.com/nirkov/AWS-PDF-Document-Conversion/blob/master/input.txt).

## Output file format:
The output is an HTML file containing a line for each input line. The format of each line is as follows:
> [<operation>: input file output file.]
 
 where:
 - Operation is one of the possible operations.
 - Input file is a link to the input PDF file.
 - Output file is a link to the image/text/HTML output file.

If an exception occurs while performing an operation on a PDF file, or the PDF file is not available, then output line for this file will be: 
> <operation>: input file <a short description of the exception>.
