# Setup - TLDR Edition

This version provides the exact steps/commands required to setup the required infrastructure. This document assumes you will be making use of the provided scripts, namely `create_aws_infrastructure.py` and `configure_eks_cluster.py`. 

## Step 1: AWS CLI & kubectl

Install and configure the `AWS CLI`. Please refer to the [general AWS CLI documentation](https://aws.amazon.com/cli/), [installation instructions](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html), and [credentials configuration instructions](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-configure.html) for this step.

You must install and configure `kubectl`, the Kubernetes command-line tool, which allows you to run commands against Kubernetes clusters. Please refer to the [official Kubernetes documentation](https://kubernetes.io/docs/tasks/tools/) for this step.

## Step 2: Python modules

Install the Python modules listed in the `aws-setup/requirements.txt` field. This can be performed in a single step using the `pip` module:

```
python3 -m pip install -r requirements.txt
```

(You may need to adjust the command above depending on how you invoke python/pip.)

## Step 2: Manual configuration (`config_aws.yaml`)

Create a `config_aws.yaml`, using the `sample_config_aws.yaml` as a reference. Provide values for the `ssh_keypair_name`, `ssh_key_path`, and `aws_profile` configuration parameters as described in `setup.md`. 

Once your configuration file has been created and is located within the `aws-setup/` directory, execute the `create_aws_infrastructure.py` script using `Python 3`. We tested this script on Windows 10 version 22H2 (OS Build 19045.3448) with Python 3.9.4 (tags/v3.9.4:1f2e308, Apr  6 2021, 13:40:21).

## Step 3: Execute `create_aws_infrastructure.py`

Execute the `create_aws_infrastructure.py` script as follows:

```
python3 create_aws_infrastructure.py --yaml ./config_aws.yaml
```

## Step 4: Execute `configure_eks_cluster.py`

Wait 10 - 15 minutes until the newly-created AWS EKS cluster becomes operational. Once the cluster has become operational, execute the `configure_eks_cluster.py` script. Just as with the `create_aws_infrastructure.py` script, the `configure_eks_cluster.py` script requires a YAML configuration file. A sample configuration file is provided in `aws-setup/sample_config_eks.yaml`. The configuration parameters for the `configure_eks_cluster.py` script are very similar to those of the `create_aws_infrastructure.py` script, with only a few minor differences. The sample configuration file is annotated with comments explaining the purpose of each configuration parameter.

Once you've created a `config_eks.yaml` file based off of the `sample_config_eks.yaml` file, you can execute the `configure_eks_cluster.py` script as follows:

```
configure_eks_cluster.py --yaml config_eks.yaml  
```

This script will install the Amazon EBS CSI Driver, which is required by OpenWhisk.

Once this script finishes execution, you must annotate Kubernetes service account with the ARN of the IAM role. Execute the following command, replacing `111122223333` with your account ID and `AmazonEKS_EBS_CSI_DriverRole` with the name of the IAM role:

```
kubectl annotate serviceaccount ebs-csi-controller-sa \
    -n kube-system \
    eks.amazonaws.com/role-arn=arn:aws:iam::111122223333:role/AmazonEKS_EBS_CSI_DriverRole
```

## Step 5: NGINX Secret

Generate the certificate and key (replace `KEY` and `CERT` with whatever you want the generated files to be named):

```
openssl req -x509 -newkey rsa:4096 -keyout KEY.pem -out CERT.pem -sha256 -days 365 -nodes
```

Next, create the `secret`. The secret must be named `"<OpenWhisk-deployment-name>-nginx"`. The name of the OpenWhisk deployment is whatever you specify to `helm` when deploying the OpenWhisk chart via `helm install <deployment_name> values.yaml .` (as described later in the documentation). Once again, replace `KEY` and `CERT` with whatever you specified when generating the files:

```
kubectl create secret tls OPENWHISK_DEPLOYMENT_NAME-nginx --cert=CERT.pem --key=KEY.pem
```

If you named your OpenWhisk deployment `owdev`, then the command would be:

```
kubectl create secret tls owdev-nginx --cert=CERT.pem --key=KEY.pem
```

## Step 6: OpenWhisk

If you have not done so already, create the $\lambda$FS "primary" client and experimental driver virtual machine using the `create_aws_infrastructure.py` script. Connect to the machine via SSH.

Navigate to the `/home/ubuntu/repos/openwhisk-deploy-kube` directory. 

### **Generate Self-Signed Certificates for OpenWhisk**

First, generate the self-signed certificates. You can change the `key.pem` and `cert.pem` filenames if desired for whatever reason. 

```
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -sha256 -days 365 -nodes
```

Next, upload to IAM (changing the `cert.pem` and `key.pem` if you changed them in the previous command):

```
aws iam upload-server-certificate --server-certificate-name ow-self-signed-test --certificate-body file://cert.pem --private-key file://key.pem
```

Verify that the upload was successful by using the command:

```
aws iam list-server-certificates
```

Add the following to your `values.yaml`. Make sure to replace the `111222333444` with your AWS account ID. Likewise, use your certificate's ARN instead of the example one:

```
whisk:
  ingress:
    ...
    type: LoadBalancer
    annotations:
      service.beta.kubernetes.io/aws-load-balancer-internal: 0.0.0.0/0
      service.beta.kubernetes.io/aws-load-balancer-ssl-cert: arn:aws:iam::111222333444:server-certificate/ow-self-signed
    ...
```

### Deploying & Updating OpenWhisk

**IMPORTANT:** Please ensure you are using the `aws` branch of the `openwhisk-deploy-kube` repository.

To deploy OpenWhisk for the first time, navigate to the `openwhisk-deploy-kube/helm/openwhisk` directory and execute the following command:

```
# Note that you can change `owdev` to be whatever you want.
# It will be the name of the OpenWhisk Kubernetes deployment.
helm install owdev -f values.yaml .
```

If you wish to update your existing OpenWhisk deployment after modifying some settings, navigate to the `openwhisk-deploy-kube/helm/openwhisk` directory and execute the following command:

```
# Change `owdev` to whatever name you assigned to your OpenWhisk Kubernetes deployment.
helm upgrade owdev -f values.yaml .
```

Shortly after you deploy your helm chart, an ELB should be automatically created. You can determine its hostname by issuing the command `kubectl get services -o wide`. 

Use the value in the the `EXTERNAL-IP` column for the nginx service and port 80 to define your wsk `apihost` property:

```
wsk -i property set --apihost http://<EXTERNAL-IP>:80
```

**IMPORTANT:** Make sure to prepend `<EXTERNAL-IP>` with `"http://"` when setting the `apihost` property as is shown in the command above. 

You may also modify the `apiHostName` and `apiHostPort` fields of the `values.yaml` file to contain the new API host and port as specified in the command above.

```
whisk:
  # Ingress defines how to access OpenWhisk from outside the Kubernetes cluster.
  # Only a subset of the values are actually used on any specific type of cluster.
  # See the "Configuring OpenWhisk section" of the docs/k8s-*.md that matches
  # your cluster type for details on what values to provide and how to get them.
  ingress:
    apiHostName: http://<EXTERNAL-IP>
    apiHostPort: 80
    apiHostProto: "https"
...
```

You may monitor the progress of the OpenWhisk deployment by inspecting the various pods.

```
kubectl get pods 
```

## Step 7: Create NameNode Deployments

Execute the following command to automatically register 20 NameNode deployments with OpenWhisk:

```
python3 /home/ubuntu/repos/hops/dev-support/whisk/whisk_helper_gcp.py -n 20 --create --memory 20000 --concurrency 4
```

