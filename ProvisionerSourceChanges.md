# Changes made to the Provisioner Source (provisioner-src directory)

### Changes made to the start.sh script

- Script located at AWSProvisioner/provisioner-src/src/main/resources/shell/start.sh.in.
- Commented out last line so that python program could end. This would normally start a monitoring script
- Possible issues: We won't see the logs directly meaning that if there is an issue with setup we won't now directly but we'll be able to monitor in other ways eventually.

### Changes made to the BasicLambdaHelper.java file

- Java file located at AWSProvisioner/provisioner-src/src/main/java/com/awslabs/aws/greengrass/provisioner/implementations/helpers/BasicLambdaHelper.java
- Commented out line  (return String.join("-", groupName, baseFunctionName);) due to the fact that each lambda was getting named differently
- This was done so that existing lambdas can be added automatically

## [REQUIRED FOR UBUNTU HOST MACHINE] Changes to BasicPythonBuilder.java

- Java File located at AWSProvisioner/provisioner-src/src/main/java/com/awslabs/aws/greengrass/provisioner/implementations/builders/BasicPythonBuilder.java
- Add/Uncomment in the following line `programAndArguments.add("--system");` at Line 123
- There's an issue with the way Ubuntu's pip works. Without the `--system` parameter, pip will not be able to install the packages.
- On a Windows host machine, make sure that this is commented out. You can checkout the windows branch and it is commented for you.

## Changes to BasicConfigFileHelper.java

- Java File located at AWSProvisioner/provisioner-src/src/main/java/com/awslabs/aws/greengrass/provisioner/implementations/helpers/BasicConfigFileHelper.java
- Force all HTTP connections and MQTT connections onto Port 443, so that we can bypass corporate IT restrictions.
- Added lines 47-51.

### Changes made to the template.sh script

- Script located at AWSProvisioner/provisioner-src/src/main/resources/shell/template.sh.in.
- Added lines 240-245 in order to install Docker on all GreenGrass Cores, since we will be running Docker containers for the most part.
- Commented out until we can get a stable cross platform way to get docker installed
