import org.apache.commons.cli.*;
import java.util.List;

public class LocalApp {

    public static void main(String[] args) {

        /****************************************************************************
         *
         *      TODO: You need to insert arguments
         *            @they_are_all_mandatory!
         *            -bucketName     <arg>   | S3 bucket name.
         *            -inputFilePath  <arg>   | Local path to tasks file.
         *            -outputFilePath <arg>   | Local path to save from S3.
         *            -n              <arg>   | Number of worker per task.
         *            -role           <arg>   | Manager role.
         *            -terminate      <arg>   | True or Flase - after the work done.
         *
         ****************************************************************************/

        // Create options
        Options optionsFlag      = new Options();
        Option S3bucketName      = new Option("bucketName"     ,true, "S3 bucket name.");
        Option localDirInputPath = new Option("inputFilePath"  ,true, "Local path to tasks file.");
        Option localDirOutPath   = new Option("outputFilePath" ,true, "Local path to save from S3.");
        Option role              = new Option("role"           ,true, "Manager role.");
        Option n                 = new Option("n"              ,true, "Number of worker per task.");
        Option terminate         = new Option("terminate"      ,true, "True or Flase - after the work done.");

        // Set all the options required option to True
        S3bucketName.setRequired(true);
        localDirInputPath.setRequired(true);
        localDirOutPath.setRequired(true);
        terminate.setRequired(true);
        role.setRequired(true);
        n.setRequired(true);

        // Set the type for parser
        S3bucketName.setType(String.class);
        localDirInputPath.setType(String.class);
        localDirOutPath.setType(String.class);
        terminate.setType(Boolean.class);
        role.setType(String.class);
        n.setType(String.class);

        // Add the options to optionsFlag
        optionsFlag.addOption(S3bucketName);
        optionsFlag.addOption(localDirInputPath);
        optionsFlag.addOption(localDirOutPath);
        optionsFlag.addOption(terminate);
        optionsFlag.addOption(role);
        optionsFlag.addOption(n);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter  = new HelpFormatter();
        CommandLine cmd;

        // get mAWS instance for connection with amazon services
        AWSUtils mAWS = AWSUtils.getInstance();

        try {
            cmd = parser.parse(optionsFlag, args);

            String inputFilePath  = cmd.getOptionValue("inputFilePath");
            String outputFilePath = cmd.getOptionValue("outputFilePath");
            String bucketName     = cmd.getOptionValue("bucketName");
            String managerRole    = cmd.getOptionValue("role");
            String number         = cmd.getOptionValue("n");

            // send tasks
            List<String> filesName = mAWS.uploadTasksFileToS3(inputFilePath, bucketName, managerRole);

            // Inform the manager about new task
            mAWS.msgToManager(bucketName, filesName, "newTask", number);

            // Get the output file from S3
            mAWS.getOutputFile(bucketName, filesName, outputFilePath);

            // Terminate the manager which should terminate all slaves
            if(cmd.getOptionValue("terminate").equals("False")) mAWS.sendTerminate();

        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", optionsFlag);
            System.exit(1);
        } catch (Exception e) {
            mAWS.sendTerminate();
            e.printStackTrace();
        }
    }
}


//-inputFilePath
//C:\\Users\\nirkov\\Desktop\\try\\
//-outputFilePath
//C:\\Users\\nirkov\\Desktop\\try\\
//-bucketName
//lastrunnirnaorproject
//-role
//ManagerPermissionsRole
//-n
//5
//-terminate
//True





