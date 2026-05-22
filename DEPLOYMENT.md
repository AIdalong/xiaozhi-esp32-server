# 小智 ESP32 Server 部署教程

## 首次部署

### 1. 环境要求
- Docker 已安装并运行
- 项目代码已下载到本地

### 2. 构建镜像
在项目根目录下执行：
```bash
docker build -f Dockerfile-server -t xiaozhi-esp32-server:server_latest .
```

### 3. 准备配置和模型文件
确保以下目录和文件存在：
- `main/xiaozhi-server/data/` - 配置文件目录
- `main/xiaozhi-server/models/SenseVoiceSmall/model.pt` - ASR模型文件

### 4. 启动容器（推荐方式：docker compose）
```bash
cd /srv/xiaozhi-server
docker compose up -d xiaozhi-esp32-server
```

---

**备用方式：直接docker run（不推荐）**
```bash
docker run -d \
  --name xiaozhi-esp32-server \
  --restart always \
  --security-opt seccomp:unconfined \
  -e TZ=Asia/Shanghai \
  -p 8000:8000 \
  -p 8003:8003 \
  -v /srv/lukka-xzserver/main/xiaozhi-server/data:/opt/xiaozhi-esp32-server/data \
  -v /srv/lukka-xzserver/main/xiaozhi-server/models/SenseVoiceSmall/model.pt:/opt/xiaozhi-esp32-server/models/SenseVoiceSmall/model.pt \
  xiaozhi-esp32-server:server_latest
```

### 5. 验证部署
检查容器状态：
```bash
docker ps --filter name=xiaozhi-esp32-server
```

查看日志：
```bash
docker logs -f xiaozhi-esp32-server
```

---

## 更新部署

当代码有更新时，按以下步骤更新部署：

### 1. 重新构建镜像
在项目根目录下执行：
```bash
docker build -f Dockerfile-server -t xiaozhi-esp32-server:server_latest .
```

### 2. 用新镜像重启容器
docker compose会自动停止旧容器并启动新容器，不需要手动删除：
```bash
cd /srv/xiaozhi-server
docker compose up -d xiaozhi-esp32-server
```

### 3. 验证更新
检查容器状态和日志确认更新成功。

---

## 端口说明

| 端口 | 用途 |
|------|------|
| 8000 | WebSocket 服务（设备连接） |
| 8003 | HTTP 服务（OTA、视觉分析接口） |

---

## 常用 Docker 命令

### 查看容器日志
```bash
docker logs -f xiaozhi-esp32-server
```

### 进入容器
```bash
docker exec -it xiaozhi-esp32-server /bin/bash
```

### 重启容器
```bash
docker restart xiaozhi-esp32-server
```

### 停止容器
```bash
docker stop xiaozhi-esp32-server
```

### 删除容器
```bash
docker rm xiaozhi-esp32-server
```
