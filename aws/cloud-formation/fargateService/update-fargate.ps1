aws cloudformation update-stack --stack-name devFargate --template-body file://aws/cloud-formation/fargateService/alb_ecs.yaml --profile vaylaapp --parameters file://aws\cloud-formation\fargateService\dev\DEV-alb-ecs-parameter.json

aws cloudformation update-stack --stack-name qa-Fargate --template-body file://aws/cloud-formation/fargateService/alb_ecs.yaml --profile vaylaapp --parameters file://aws\cloud-formation\fargateService\qa\QA-alb-ecs-parameter.json