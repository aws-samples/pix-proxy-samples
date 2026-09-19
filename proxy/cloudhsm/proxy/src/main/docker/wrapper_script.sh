#!/bin/bash
#
# Patched fork of aws-samples/pix-proxy-samples.
# Changes vs. upstream (see VERIFICATION.md and upstream issue #17):
#   1. Configure EVERY ACTIVE HSM in the cluster, not just Hsms[0].
#   2. Bound the readiness wait with a timeout and fail fast instead of hanging forever.
#   3. exec the JVM so it becomes PID 1 and receives SIGTERM (graceful shutdown).
#
set -uo pipefail

READY_TIMEOUT_SECS="${HSM_CLIENT_READY_TIMEOUT_SECS:-90}"
CLIENT_LOG=/tmp/cloudhsm_client_start.log

die() { echo "FATAL: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Trust anchor for the cluster (the customer CA you signed the cluster CSR with)
# ---------------------------------------------------------------------------
[ -n "${HSM_CUSTOMER_CA:-}" ] || die "HSM_CUSTOMER_CA is empty - map the CloudHSMCustomerCA parameter into the task"
echo "$HSM_CUSTOMER_CA" > /opt/cloudhsm/etc/customerCA.crt

# ---------------------------------------------------------------------------
# 2. Discover and configure ALL active HSMs
#
#    Upstream used --query "Clusters[0].Hsms[0].EniIp", i.e. only the FIRST HSM.
#    That silently defeats multi-AZ high availability: every task pins itself to one
#    HSM, the others serve no traffic while still being billed, and an HSM
#    replacement (which changes the ENI IP) takes the fleet down until restarted.
# ---------------------------------------------------------------------------
[ -n "${HSM_CLUSTER_ID:-}" ] || die "HSM_CLUSTER_ID is empty - map the CloudHSMClusterId parameter into the task"
[ -n "${AWS_DEFAULT_REGION:-}" ] || die "AWS_DEFAULT_REGION is empty"

HSM_IPS=$(aws cloudhsmv2 describe-clusters \
            --region "$AWS_DEFAULT_REGION" \
            --filter clusterIds="$HSM_CLUSTER_ID" \
            --query "Clusters[0].Hsms[?State=='ACTIVE'].EniIp" \
            --output text) || die "describe-clusters failed (check the cloudhsmv2:DescribeClusters IAM permission)"

[ -n "$HSM_IPS" ] || die "no ACTIVE HSM found in cluster $HSM_CLUSTER_ID"

HSM_COUNT=0
for ip in $HSM_IPS; do
    echo "* configuring HSM $ip"
    /opt/cloudhsm/bin/configure -a "$ip" || die "configure -a $ip failed"
    HSM_COUNT=$((HSM_COUNT + 1))
done
echo "* configured $HSM_COUNT active HSM(s)"

# ---------------------------------------------------------------------------
# 3. Start the client daemon and wait for readiness, WITH A TIMEOUT
#
#    Upstream looped `while true` with no timeout: a wrong security group, a bad
#    customerCA.crt or an empty HSM IP left the container neither ready nor exited,
#    so ECS could not fail fast and retry.
# ---------------------------------------------------------------------------
/opt/cloudhsm/bin/cloudhsm_client /opt/cloudhsm/etc/cloudhsm_client.cfg &> "$CLIENT_LOG" &
CLIENT_PID=$!

echo "* waiting up to ${READY_TIMEOUT_SECS}s for the CloudHSM client to become ready"
deadline=$(( $(date +%s) + READY_TIMEOUT_SECS ))
while true; do
    if grep -q 'libevmulti_init: Ready !' "$CLIENT_LOG" 2>/dev/null; then
        echo "* CloudHSM client ready"
        break
    fi
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
        echo "--- $CLIENT_LOG ---" >&2; tail -50 "$CLIENT_LOG" >&2
        die "CloudHSM client process exited before becoming ready"
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "--- $CLIENT_LOG ---" >&2; tail -50 "$CLIENT_LOG" >&2
        die "CloudHSM client not ready after ${READY_TIMEOUT_SECS}s. Common causes: the task security group is not allowed inbound on the CloudHSM cluster security group (TCP 2223-2225), a malformed customerCA.crt, or a wrong cluster id."
    fi
    sleep 0.5
done

# Upstream had a bare `sleep 10` here, which suggests the 'Ready !' log line alone is not
# a sufficient readiness signal. Kept as a short, CONFIGURABLE settle delay rather than a
# hardcoded 10s. Set HSM_CLIENT_SETTLE_SECS=0 once you have verified a real capability
# probe (e.g. a login + findKey round trip) is in place.
SETTLE_SECS="${HSM_CLIENT_SETTLE_SECS:-10}"
if [ "$SETTLE_SECS" -gt 0 ]; then
    echo "* settling for ${SETTLE_SECS}s"
    sleep "$SETTLE_SECS"
fi

# ---------------------------------------------------------------------------
# 4. Hand over to the JVM
#
#    `exec` makes the JVM PID 1 so that SIGTERM from ECS reaches it directly.
#    Without it the signal goes to this shell and graceful shutdown can be lost,
#    meaning in-flight payment requests are killed abruptly.
# ---------------------------------------------------------------------------
echo "* starting application"
exec java ${JAVA_OPTS:-} -jar application.jar
