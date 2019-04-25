import com.amazonaws.services.sqs.AmazonSQS;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class ActionListenerImp implements ActionListener {
    private final String mTasksQueueUrl;
    private final String mMessageRecieptHandle;
    private final AmazonSQS mSQS;

    ActionListenerImp(final String messageRecieptHandle, AmazonSQS sqs, String tasksQueueUrl){
        super();
        mMessageRecieptHandle = messageRecieptHandle;
        mSQS = sqs;
        mTasksQueueUrl = tasksQueueUrl;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        System.out.println("Visibility update to minute");
        mSQS.changeMessageVisibility(mTasksQueueUrl, mMessageRecieptHandle, 60);
    }
}
