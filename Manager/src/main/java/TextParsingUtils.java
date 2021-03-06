import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class TextParsingUtils {
    final private String LOCAL_PATH_TO_SAVE_FILES = "./";
    public OutputFileMaker mOutputFileMaker;

    public TextParsingUtils(){
        mOutputFileMaker = new OutputFileMaker();
    }

    /**                                          @ParsTextToPairs
     * The function receive path to txt file which each line in it consists of two parts :
     * 1.task (ToImage, ToHTML	or ToText)
     * 2.url to pdf file.
     * parsing it and return 'bank' list of 'msgAsPair' - an object containing the taskType as String
     * and String of the pdf url.
     * @param input
     * @return bank
     * @throws IOException
     */
    public List<MsgAsPair<String> > ParsTextToPairs(InputStream input) throws IOException {
        BufferedReader bufRead = new BufferedReader(new InputStreamReader(input));
        List<MsgAsPair<String> > bank = new ArrayList<MsgAsPair<String> >();
        String myLine = null;
        while ( (myLine = bufRead.readLine()) != null) {
            if(myLine != null && !myLine.isEmpty() && myLine.contains("\t")){
                String[] splitedLine = myLine.split("\t");
                bank.add(new MsgAsPair<String>(splitedLine[0], splitedLine[1]));
            }
        }
        return bank;
    }

    /**                                           @PdfToImage
     * Save image of the first page of the pdf that downloaded from given url, locally in @LOCAL_PATH_TO_SAVE_FILES.
     * @param url
     */
    public String PdfToImage(String url, String fileName) throws Exception {
        if(!isValid(url) || !isValid(fileName) || !fileName.contains(".png")) throw new IllegalArgumentException();
        String filePath = "";
        PDDocument document;
        try{
            document = PDDocument.load(new URL(url).openStream());
        }catch(Exception e){
            throw new Exception("Broken url :  " + url);
        }

        PDFRenderer pdfRenderer = new PDFRenderer(document);

        try{
            BufferedImage bim = pdfRenderer.renderImageWithDPI(0, 300, ImageType.RGB);
            filePath = makeValidStringPath(LOCAL_PATH_TO_SAVE_FILES) + fileName;
            ImageIOUtil.writeImage(bim, filePath, 300);
        }catch(Exception e){
            throw new Exception("IO problem");
        }

        document.close();
        return filePath;
    }

    /**                                           @PdfToHTML
     * Save html file with the text of the pdf that downloaded from given url, locally in @LOCAL_PATH_TO_SAVE_FILES.
     * @param url
     * @throws IOException
     */
    public String PdfToHTML(String url) throws Exception {
        return "<!DOCTYPE html><br><html><br><body><br>" +
                "<p><br>"+
                PdfToText(url) +
                "</p><br>" +
                "</body></html>";
    }

    /**                                            @PdfToTxt
     * Save txt file with the text of the pdf that downloaded from given url, localy in LOCAL_PATH_TO_SAVE_FILES.
     * @param mPDFurl
     * @throws IOException
     */
    public String PdfToTxt(String mPDFurl) throws Exception {
        return PdfToText(mPDFurl);
    }

    public String extractFileName(String urlToPDF){
        if(urlToPDF == null) throw new NullPointerException();
        if(urlToPDF.isEmpty()) throw new IllegalArgumentException("The url is enmpty");
        return urlToPDF.contains("/") ? urlToPDF.substring( urlToPDF.lastIndexOf('/')+1, urlToPDF.lastIndexOf('.'))
                : urlToPDF.substring(0, urlToPDF.lastIndexOf('.'));
    }


    /**                              @private_methods                               **/

    private String PdfToText(String url) throws Exception {
        final PDDocument document = downloadPDF(url);
        COSDocument cosDoc = document.getDocument();
        String text = "";
        try{
            PDFTextStripper pdfStripper = new PDFTextStripper();
            PDDocument pdDoc = new PDDocument(cosDoc);
            pdfStripper.setStartPage(0);
            pdfStripper.setEndPage(document.getNumberOfPages());
            text = pdfStripper.getText(pdDoc);
            pdDoc.close();
        }catch(Exception e){
            throw new Exception("IO problem");
        }
        document.close();

        return text;
    }

    private String makeValidStringPath(String path) {
        if(path == null || path.equals("")){
            throw new IllegalArgumentException("The path is empty String or null");
        }

        return (path).replace("\\", "/");
    }

    private PDDocument downloadPDF(String url) throws Exception {
        try{
            return PDDocument.load(new URL(url).openStream());
        }catch(Exception e){
            throw new Exception("Broken url");
        }
    }

    private boolean isValid(String s){
        return s == null || s.isEmpty() ? false : true;
    }


    /**                              @private_classes                               **/

    private class OutputFileMaker{
        private String  mOutputFile;
        private boolean taskEnd;

        OutputFileMaker() {
            taskEnd     = false;
            mOutputFile = "";
        }

        OutputFileMaker(String line){
            taskEnd     = false;
            mOutputFile += line;
        }

        public boolean addLine(String line){
            if(line == null || line.isEmpty() || taskEnd) return false;
            mOutputFile += line;
            return true;
        }

        public boolean addLine(String task, String url, String s3Path){
            if(task == null || task.isEmpty() || url == null || url.isEmpty()
                    || s3Path == null || s3Path.isEmpty() || taskEnd){
                return false;
            }
            mOutputFile += task + ":\t" + url + "\t" + s3Path;
            return true;
        }

        public String getFileAsHTMLFormat(){
            taskEnd = false;
            return "<!DOCTYPE html><br><html><br><body><br>" +
                    "<p><br>"+
                    mOutputFile +
                    "</p><br>" +
                    "</body></html>";
        }

        public String getOutputFile(){ return mOutputFile;}

        public boolean needToStartNewTask(){ return taskEnd;}

        public void initNewTask(){
            taskEnd = false;
            mOutputFile = "";
        }

        public boolean initNewTask(String line){
            taskEnd = false;
            mOutputFile = "";
            return addLine(line);
        }

        public boolean initNewTask(String task, String url, String s3Path){
            taskEnd = false;
            mOutputFile = "";
            return addLine(task, url, s3Path);
        }
    }
}

