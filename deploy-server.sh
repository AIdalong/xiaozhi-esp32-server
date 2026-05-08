#!/bin/bash
set -e

echo "====================================="
echo "开始部署 xiaozhi-esp32-server 服务"
echo "====================================="

# 1. 基于当前目录编译docker镜像
echo ""
echo "步骤 1/3: 构建 Docker 镜像..."
docker build -f Dockerfile-server -t xiaozhi-esp32-server:server_latest .

if [ $? -eq 0 ]; then
    echo "✓ 镜像构建成功"
else
    echo "✗ 镜像构建失败，终止部署"
    exit 1
fi

# 2. 切换到部署目录并使用docker-compose重新部署
echo ""
echo "步骤 2/3: 切换到部署目录 /srv/xiaozhi-server"
cd /srv/xiaozhi-server

echo ""
echo "步骤 3/3: 使用 docker-compose 重新部署服务..."
docker compose up -d xiaozhi-esp32-server

if [ $? -eq 0 ]; then
    echo "✓ 服务部署成功"
else
    echo "✗ 服务部署失败"
    exit 1
fi

echo ""
echo "====================================="
echo "部署完成！"
echo "====================================="
echo ""
echo "验证容器状态："
docker ps --filter name=xiaozhi-esp32-server
echo ""
echo "查看日志命令：docker logs -f xiaozhi-esp32-server"
echo ""
