resource "kubernetes_namespace_v1" "pod99" {

  metadata {

    name = var.domain_name
    labels = {
 "app.kubernetes.io/part-of" = "pod99" 
}

  
}


}

resource "kubernetes_service_account_v1" "auth" {

  metadata {
 name = "authorization-limit-service"
 namespace = kubernetes_namespace_v1.pod99.metadata[0].name 
}


}

resource "kubernetes_service_account_v1" "accounting" {

  metadata {
 name = "accounting-service"
 namespace = kubernetes_namespace_v1.pod99.metadata[0].name 
}


}

resource "kubernetes_service_account_v1" "acl" {

  metadata {
 name = "core-banking-acl-service"
 namespace = kubernetes_namespace_v1.pod99.metadata[0].name 
}


}


resource "kubernetes_deployment_v1" "authorization" {

  metadata {
 name = "authorization-limit-service"
 namespace = kubernetes_namespace_v1.pod99.metadata[0].name
    labels = {
app="authorization-limit-service"
}
 
}

  spec {

    replicas = 3
    strategy {
 type = "RollingUpdate"
 rolling_update {
 max_unavailable = 0
 max_surge = 1 
}
 
}

    selector {
 match_labels = {
app="authorization-limit-service"
}
 
}

    template {

      metadata {
labels={
app="authorization-limit-service"
}

}

      spec {

        service_account_name = kubernetes_service_account_v1.auth.metadata[0].name
        termination_grace_period_seconds = 45
        container {

          name = "authorization"
          image = var.authorization_image
          image_pull_policy = "IfNotPresent"
          port {
 container_port = 8080 
}

          env {
name="AWS_REGION"
value=var.region
}

          env {
name="DDB_TABLE"
value=var.authorization_table
}

          resources {
requests={
cpu="250m",memory="512Mi"
}

limits={
cpu="1500m",memory="1024Mi"
}

}

          readiness_probe {
http_get {
path="/actuator/health"
port=8080
}

initial_delay_seconds=20
period_seconds=10
}

          liveness_probe {
http_get {
path="/actuator/health"
port=8080
}

initial_delay_seconds=40
period_seconds=15
}

          security_context {
allow_privilege_escalation=false
run_as_non_root=true
read_only_root_filesystem=true
capabilities {
drop=["ALL"]
}

}

        
}

      
}

    
}

  
}

  wait_for_rollout = true

}

resource "kubernetes_service_v1" "authorization_internal_nlb" {

  metadata {

    name = "authorization-internal-nlb"
    namespace = kubernetes_namespace_v1.pod99.metadata[0].name
    annotations = {

      "service.beta.kubernetes.io/aws-load-balancer-scheme" = "internal"
      "service.beta.kubernetes.io/aws-load-balancer-nlb-target-type" = "ip"
      "service.beta.kubernetes.io/aws-load-balancer-healthcheck-path" = "/actuator/health"
    
}

  
}

  spec {

    type = "LoadBalancer"
    load_balancer_class = "eks.amazonaws.com/nlb"
    selector = {
app="authorization-limit-service"
}

    port {
name="http"
port=80
target_port="8080"
protocol="TCP"
}

  
}

  wait_for_load_balancer = true

}

resource "kubernetes_deployment_v1" "accounting" {

  metadata {
name="accounting-service"
namespace=kubernetes_namespace_v1.pod99.metadata[0].name
}

  spec {

    replicas=2
    selector{
match_labels={
app="accounting-service"
}

}

    template{

      metadata{
labels={
app="accounting-service"
}

}

      spec{

        service_account_name=kubernetes_service_account_v1.accounting.metadata[0].name
        container{

          name="accounting"
          image=var.accounting_image
          image_pull_policy="IfNotPresent"
          env{
name="AWS_REGION"
value=var.region
}

          env{
name="ACCOUNTING_TABLE"
value=var.accounting_table
}

          env{
name="ACCOUNTING_SQS_URL"
value=var.accounting_queue_url
}

          resources{
requests={
cpu="100m",memory="256Mi"
}

limits={
cpu="500m",memory="768Mi"
}

}

          security_context {
allow_privilege_escalation=false
run_as_non_root=true
read_only_root_filesystem=true
capabilities{
drop=["ALL"]
}

}

        
}

      
}

    
}

  
}


}

resource "kubernetes_deployment_v1" "acl" {

  metadata{
name="core-banking-acl-service"
namespace=kubernetes_namespace_v1.pod99.metadata[0].name
}

  spec{

    replicas=2
    selector{
match_labels={
app="core-banking-acl-service"
}

}

    template{

      metadata{
labels={
app="core-banking-acl-service"
}

}

      spec{

        service_account_name=kubernetes_service_account_v1.acl.metadata[0].name
        container{

          name="acl"
          image=var.acl_image
          image_pull_policy="IfNotPresent"
          port{
container_port=8082
}

          env{
name="SERVER_PORT"
value="8082"
}

          resources{
requests={
cpu="100m",memory="256Mi"
}

limits={
cpu="500m",memory="512Mi"
}

}

          readiness_probe{
http_get{
path="/actuator/health"
port=8082
}

initial_delay_seconds=20
}

          liveness_probe{
http_get{
path="/actuator/health"
port=8082
}

initial_delay_seconds=35
}

          security_context{
allow_privilege_escalation=false
run_as_non_root=true
read_only_root_filesystem=true
capabilities{
drop=["ALL"]
}

}

        
}

      
}

    
}

  
}


}

resource "kubernetes_service_v1" "acl"{

  metadata{
name="core-banking-acl-service"
namespace=kubernetes_namespace_v1.pod99.metadata[0].name
}

  spec{
selector={
app="core-banking-acl-service"
}

port{
port=8082
target_port="8082"
}

type="ClusterIP"
}


}

resource "kubernetes_horizontal_pod_autoscaler_v2" "auth"{

  metadata{
name="authorization-hpa"
namespace=kubernetes_namespace_v1.pod99.metadata[0].name
}

  spec{

    scale_target_ref{
api_version="apps/v1"
kind="Deployment"
name=kubernetes_deployment_v1.authorization.metadata[0].name
}

    min_replicas=3
max_replicas=30
    metric{
type="Resource"
resource{
name="cpu"
target{
type="Utilization"
average_utilization=65
}

}

}

  
}


}

resource "kubernetes_horizontal_pod_autoscaler_v2" "accounting"{

  metadata{
name="accounting-hpa"
namespace=kubernetes_namespace_v1.pod99.metadata[0].name
}

  spec{

    scale_target_ref{
api_version="apps/v1"
kind="Deployment"
name=kubernetes_deployment_v1.accounting.metadata[0].name
}

    min_replicas=2
max_replicas=12
    metric{
type="Resource"
resource{
name="cpu"
target{
type="Utilization"
average_utilization=70
}

}

}

  
}


}

resource "kubernetes_pod_disruption_budget_v1" "authorization"{

  metadata{
name="authorization-pdb"
namespace=kubernetes_namespace_v1.pod99.metadata[0].name
}

  spec{
min_available="2"
selector{
match_labels={
app="authorization-limit-service"
}

}

}


}

output "private_nlb_hostname" {
value=kubernetes_service_v1.authorization_internal_nlb.status[0].load_balancer[0].ingress[0].hostname
}

output "namespace" {
value=kubernetes_namespace_v1.pod99.metadata[0].name
}

