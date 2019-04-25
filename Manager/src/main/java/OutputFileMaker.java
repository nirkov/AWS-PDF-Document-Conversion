public class OutputFileMaker{
    private String  mOutputFile;
    private boolean taskEnd;

    OutputFileMaker() {
        taskEnd     = false;
        mOutputFile = "";
    }

    public boolean addLine(String line){
        if(line == null || line.isEmpty() || taskEnd) return false;
        mOutputFile += line;
        return true;
    }

    public boolean addLine(String task, String url, String msg, boolean isDone){
        if(task == null || task.isEmpty() || url == null || url.isEmpty()
                || msg == null || msg.isEmpty() || taskEnd){
            return false;
        }

        // Turn the string to url link in html  //TODO : CHECK!
        if(isDone){
            msg = "<a href=" + msg + ">" + msg + "</a>";
        }else{
            msg =  "<span style=\"color: red;\">" + msg + "</span>";
        }

        mOutputFile += "<p> " +
                "<pre class=\"tab\">" +
                "<span style=\"color: blue;\">" + task +" : " + "</span>" +
                url + " " +
                msg +
                "</pre>" +
                "<br />" +
                "</p>";
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


}