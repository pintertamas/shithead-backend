resource "aws_ecs_cluster" "game_cluster" {
  name = "${var.project_name}-${var.ecs_cluster_name}"
}
