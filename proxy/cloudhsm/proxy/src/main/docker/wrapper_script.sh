#!/bin/bash

# Start CloudHSM Client
echo "$HSM_CUSTOMER_CA" > /opt/cloudhsm/etc/customerCA.crt
HSM_IP=`aws cloudhsmv2 describe-clusters --region $AWS_DEFAULT_REGION --filter clusterIds=$HSM_CLUSTER_ID --query "Clusters[0].Hsms[0].EniIp" --output text`
/opt/cloudhsm/bin/configure -a $HSM_IP
/opt/cloudhsm/bin/cloudhsm_client /opt/cloudhsm/etc/cloudhsm_client.cfg &> /tmp/cloudhsm_client_start.log &
# wait for startup
while true
do
    if grep 'libevmulti_init: Ready !' /tmp/cloudhsm_client_start.log &> /dev/null
    then
        echo "[OK]"
        break
    fi
    sleep 0.5
done
echo -e "\n* CloudHSM client started successfully ... \n"

echo "safe sleeping... 10s"
sleep 10
echo "awake and resuming..."

# Start application
java -jar application.jar