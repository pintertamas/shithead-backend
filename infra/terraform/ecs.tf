#module "ecs_singleton_service" {
#  source             = "git::https://github.com/7Factor/terraform-fargate-ecs-singleton-task.git"
#  vpc_id             = var.vpc_id
#  app_name           = "game-server"
#  app_port           = 8080
#  cpu                = 256
#  memory             = 512
#  desired_task_count = 1
#  container_definition = jsonencode([
#    {
#      name      = "game-server"
#      image     = "your-ecr-repo/game-server:latest"
#      essential = true
#      portMappings = [
#        {
#          containerPort = 8080
#          hostPort      = 8080
#        }
#      ]
#    }
#  ])
#}
