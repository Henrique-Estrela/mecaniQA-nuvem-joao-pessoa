terraform {
  required_version = ">= 1.5.0"

  required_providers {
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 3.0"
    }
  }
}

provider "docker" {}

resource "docker_network" "mecaniqa" {
  name = "mecaniqa"
}

resource "docker_volume" "mysql_data" {
  name = "mecaniqa_mysql_data"
}

resource "docker_volume" "redis_data" {
  name = "mecaniqa_redis_data"
}

resource "docker_image" "mysql" {
  name = "mecaniqa-mysql:8.0"

  build {
    context    = "${path.module}/.."
    dockerfile = "Dockerfile.mysql"
  }
}

resource "docker_image" "redis" {
  name = "mecaniqa-redis:7"

  build {
    context    = "${path.module}/.."
    dockerfile = "Dockerfile.redis"
  }
}

resource "docker_image" "api" {
  name = "mecaniqa-api:1.0.0"

  build {
    context    = "${path.module}/../Java-app"
    dockerfile = "Dockerfile"
  }
}

resource "docker_container" "mysql" {
  name  = "mecaniqa-mysql"
  image = docker_image.mysql.image_id

  env = [
    "MYSQL_ROOT_PASSWORD=${var.mysql_root_password}",
    "MYSQL_DATABASE=mecaniqa",
  ]

  ports {
    internal = 3306
    external = 3306
  }

  volumes {
    volume_name    = docker_volume.mysql_data.name
    container_path = "/var/lib/mysql"
  }

  networks_advanced {
    name = docker_network.mecaniqa.name
  }
}

resource "docker_container" "redis" {
  name    = "mecaniqa-redis"
  image   = docker_image.redis.image_id
  command = ["redis-server", "--appendonly", "yes"]

  ports {
    internal = 6379
    external = 6379
  }

  volumes {
    volume_name    = docker_volume.redis_data.name
    container_path = "/data"
  }

  networks_advanced {
    name = docker_network.mecaniqa.name
  }
}

resource "docker_container" "api" {
  name       = "mecaniqa-api"
  image      = docker_image.api.image_id
  depends_on = [docker_container.mysql, docker_container.redis]

  env = [
    "SPRING_DATASOURCE_URL=jdbc:mysql://mecaniqa-mysql:3306/mecaniqa",
    "SPRING_DATASOURCE_USERNAME=root",
    "SPRING_DATASOURCE_PASSWORD=${var.mysql_root_password}",
    "SPRING_DATA_REDIS_HOST=mecaniqa-redis",
    "SPRING_DATA_REDIS_PORT=6379",
  ]

  ports {
    internal = 8080
    external = 8080
  }

  networks_advanced {
    name = docker_network.mecaniqa.name
  }
}

variable "mysql_root_password" {
  description = "MySQL root password."
  type        = string
  sensitive   = true
  default     = "root"
}

output "network_name" {
  value = docker_network.mecaniqa.name
}