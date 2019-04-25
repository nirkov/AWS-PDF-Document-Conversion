public class MsgAsPair<T>{
    private T mTask;
    private T mPdfPath;

    public MsgAsPair(T task, T path) {
        mTask    = task;
        mPdfPath = path;
    }

    public T getTask(){return mTask;}

    public T getPdfPath(){return mPdfPath;}

    public void setTask(T task){ mTask = task;}

    public void setPdfPath(T path){ mPdfPath = path;}
}