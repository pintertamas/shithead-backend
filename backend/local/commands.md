#### Log into ECR
```aws ecr get-login-password --region eu-central-1 | docker login --username AWS --password-stdin 356408223209.dkr.ecr.eu-central-1.amazonaws.com```

#### Pull the latest image
```docker pull 356408223209.dkr.ecr.eu-central-1.amazonaws.com/shithead-repository:latest```

