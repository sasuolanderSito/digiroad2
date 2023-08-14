# DEV
aws cloudformation update-stack --stack-name dev-Taskdefinition --template-body file://aws/cloud-formation/taskdefinition/dev-create-taskdefinition.yaml --profile vaylaapp --parameters file://aws/cloud-formation/taskdefinition/dev-taskdefinition-parameter.json --capabilities CAPABILITY_IAM
# QA
aws cloudformation update-stack --stack-name qa-Taskdefinition --template-body file://aws/cloud-formation/taskdefinition/qa-create-taskdefinition.yaml --profile vaylaapp --parameters file://aws/cloud-formation/taskdefinition/qa-taskdefinition-parameter.json --capabilities CAPABILITY_IAM
